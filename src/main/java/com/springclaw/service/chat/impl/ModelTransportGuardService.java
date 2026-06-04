package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一管理模型传输层故障、冷却期与可用性判断。
 */
@Service
public class ModelTransportGuardService {

    private static final Logger log = LoggerFactory.getLogger(ModelTransportGuardService.class);

    private final ChatResponsePolicyService chatResponsePolicyService;
    private final long cooldownMillis;
    private final long providerFailureCooldownThreshold;
    private final ConcurrentMap<String, AtomicLong> providerDisabledUntil = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicReference<String>> providerLastReason = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> providerConsecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> modelDisabledUntil = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicReference<String>> modelLastReason = new ConcurrentHashMap<>();

    @Autowired
    public ModelTransportGuardService(ChatResponsePolicyService chatResponsePolicyService,
                                      @Value("${springclaw.chat.model-transport-cooldown-seconds:90}") long cooldownSeconds,
                                      @Value("${springclaw.chat.provider-failure-cooldown-threshold:2}") long providerFailureCooldownThreshold) {
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.cooldownMillis = Math.max(10L, cooldownSeconds) * 1000L;
        this.providerFailureCooldownThreshold = Math.max(1L, providerFailureCooldownThreshold);
    }

    ModelTransportGuardService(ChatResponsePolicyService chatResponsePolicyService,
                               long cooldownSeconds) {
        this(chatResponsePolicyService, cooldownSeconds, 2L);
    }

    public boolean isModelCallEnabled(AiProviderService.ActiveChatClient activeClient) {
        return activeClient.available()
                && activeClient.chatClient() != null
                && !isCoolingDown(activeClient.providerId());
    }

    public void markFailure(String providerId, Throwable throwable) {
        markProviderFailure(providerId, throwable);
    }

    public void markProviderFailure(String providerId, Throwable throwable) {
        if (!isTransportFailure(throwable)) {
            return;
        }
        String reason = rootMessage(throwable);
        lastReason(providerId).set(reason);
        if (!shouldCooldownProvider(throwable, reason)) {
            long consecutiveFailures = providerFailureCount(providerId).incrementAndGet();
            if (consecutiveFailures < providerFailureCooldownThreshold) {
                log.warn("检测到模型请求超时/连接中断，暂不熔断整个 provider；failure={}/{}, provider={}, reason={}",
                        consecutiveFailures, providerFailureCooldownThreshold, providerId, reason);
                return;
            }
            log.warn("模型 provider 连续发生传输异常，进入短冷却；failure={}, provider={}, reason={}",
                    consecutiveFailures, providerId, reason);
            cooldownProvider(providerId, reason);
            return;
        }
        providerFailureCount(providerId).incrementAndGet();
        cooldownProvider(providerId, reason);
    }

    public void markModelFailure(String providerId, String model, Throwable throwable) {
        if (!isTransportFailure(throwable) || !StringUtils.hasText(model)) {
            return;
        }
        String key = modelKey(providerId, model);
        String reason = rootMessage(throwable);
        modelLastReason(key).set(reason);
        long disabledUntil = System.currentTimeMillis() + cooldownMillis;
        long previous = modelDisabledUntil(key).getAndUpdate(current -> Math.max(current, disabledUntil));
        if (disabledUntil > previous) {
            log.warn("检测到模型临时异常，provider={}, model={}, {} 秒内跳过该模型。reason={}",
                    providerId, model, cooldownMillis / 1000L, reason);
        }
    }

    public void markSuccess(String providerId, String model) {
        providerFailureCount(providerId).set(0L);
    }

    public String disabledModelReason(AiProviderService.ActiveChatClient activeClient) {
        if (!activeClient.available()) {
            return activeClient.unavailableReason();
        }
        if (isCoolingDown(activeClient.providerId())) {
            String reason = lastReason(activeClient.providerId()).get();
            if (StringUtils.hasText(reason)) {
                return chatResponsePolicyService.simplifyFailureReason(reason);
            }
            return "模型服务临时不可用，已临时熔断远程模型调用。";
        }
        String reason = lastReason(activeClient.providerId()).get();
        if (StringUtils.hasText(reason)) {
            return chatResponsePolicyService.simplifyFailureReason(reason);
        }
        return "模型服务这次没有返回可用结果。";
    }

    public boolean isModelCoolingDown(String providerId, String model) {
        if (!StringUtils.hasText(model)) {
            return false;
        }
        return System.currentTimeMillis() < modelDisabledUntil(modelKey(providerId, model)).get();
    }

    public boolean isProviderCoolingDown(String providerId) {
        return isCoolingDown(providerId);
    }

    public String disabledModelPlanReason(AiProviderService.ActiveChatClient activeClient) {
        return isCoolingDown(activeClient.providerId())
                ? "模型服务临时不可用，跳过计划阶段。"
                : "模型未启用，跳过计划阶段。";
    }

    public String disabledModelActionReason(AiProviderService.ActiveChatClient activeClient) {
        return isCoolingDown(activeClient.providerId())
                ? "模型服务临时不可用，跳过行动阶段。"
                : "模型未启用，跳过行动阶段。";
    }

    private boolean isCoolingDown(String providerId) {
        return System.currentTimeMillis() < disabledUntil(providerId).get();
    }

    private void cooldownProvider(String providerId, String reason) {
        long disabledUntil = System.currentTimeMillis() + cooldownMillis;
        long previous = disabledUntil(providerId).getAndUpdate(current -> Math.max(current, disabledUntil));
        if (disabledUntil > previous) {
            log.warn("检测到模型服务临时异常，provider={}, {} 秒内跳过该 provider，优先尝试备用 provider/model。reason={}",
                    providerId, cooldownMillis / 1000L, reason);
        }
    }

    public boolean isTransportFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ResourceAccessException
                    || current instanceof TransientAiException
                    || current instanceof SSLException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof IOException) {
                return true;
            }
            if (looksLikeTemporaryUpstreamFailure(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean looksLikeTemporaryUpstreamFailure(String message) {
        String text = safe(message).toLowerCase();
        return text.contains("http 503")
                || text.contains("http 502")
                || text.contains("http 504")
                || text.contains("http 429")
                || text.contains("service unavailable")
                || text.contains("rate limit")
                || text.contains("too many requests")
                || text.contains("no available account")
                || looksLikeConnectionClosedEarly(text);
    }

    private boolean shouldCooldownProvider(Throwable throwable, String reason) {
        String text = safe(reason).toLowerCase();
        if (text.contains("read timed out")
                || text.contains("sockettimeoutexception")
                || looksLikeConnectionClosedEarly(text)) {
            return false;
        }
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }

    private boolean looksLikeConnectionClosedEarly(String text) {
        String lower = safe(text).toLowerCase();
        return lower.contains("eof reached while reading")
                || lower.contains("premature eof")
                || lower.contains("premature end")
                || lower.contains("premature close")
                || lower.contains("prematurely closed")
                || lower.contains("unexpected end of file");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable last = throwable;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        return last == null ? "" : safe(last.getMessage());
    }

    private AtomicLong disabledUntil(String providerId) {
        return providerDisabledUntil.computeIfAbsent(providerId, key -> new AtomicLong(0L));
    }

    private AtomicReference<String> lastReason(String providerId) {
        return providerLastReason.computeIfAbsent(providerId, key -> new AtomicReference(""));
    }

    private AtomicLong providerFailureCount(String providerId) {
        return providerConsecutiveFailures.computeIfAbsent(providerId, key -> new AtomicLong(0L));
    }

    private AtomicLong modelDisabledUntil(String key) {
        return modelDisabledUntil.computeIfAbsent(key, ignored -> new AtomicLong(0L));
    }

    private AtomicReference<String> modelLastReason(String key) {
        return modelLastReason.computeIfAbsent(key, ignored -> new AtomicReference(""));
    }

    private String modelKey(String providerId, String model) {
        return safe(providerId) + "::" + safe(model).trim().toLowerCase();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
