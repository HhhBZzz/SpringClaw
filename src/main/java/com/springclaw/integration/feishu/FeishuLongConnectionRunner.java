package com.springclaw.integration.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import com.lark.oapi.ws.Client;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.webhook.WebhookRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书长连接模式入口。
 *
 * 说明：
 * 1. 启用后，不再依赖公网 Webhook 回调地址。
 * 2. 仅替换“接收消息入口”，核心 OPAR 与工具/记忆链路保持不变。
 */
@Component
@ConditionalOnProperty(prefix = "springclaw.channel.feishu.long-connection", name = "enabled", havingValue = "true")
public class FeishuLongConnectionRunner implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionRunner.class);
    private static final String MESSAGE_DEDUP_KEY_PREFIX = "springclaw:feishu:message:";

    private final WebhookRouterService webhookRouterService;
    private final StringRedisTemplate redisTemplate;
    private final String appId;
    private final String appSecret;
    private final String domain;
    private final String verificationToken;
    private final String encryptKey;
    private final long dedupSeconds;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Long> recentMessageIdCache = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "feishu-long-connection-worker");
        thread.setDaemon(true);
        return thread;
    });

    private Client wsClient;

    public FeishuLongConnectionRunner(WebhookRouterService webhookRouterService,
                                      StringRedisTemplate redisTemplate,
                                      @Value("${springclaw.channel.feishu.app-id:}") String appId,
                                      @Value("${springclaw.channel.feishu.app-secret:}") String appSecret,
                                      @Value("${springclaw.channel.feishu.long-connection.domain:}") String domain,
                                      @Value("${springclaw.channel.feishu.verification-token:}") String verificationToken,
                                      @Value("${springclaw.channel.feishu.encrypt-key:}") String encryptKey,
                                      @Value("${springclaw.channel.feishu.long-connection.dedup-seconds:120}") long dedupSeconds) {
        this.webhookRouterService = webhookRouterService;
        this.redisTemplate = redisTemplate;
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.domain = domain == null ? "" : domain.trim();
        this.verificationToken = verificationToken == null ? "" : verificationToken.trim();
        this.encryptKey = encryptKey == null ? "" : encryptKey.trim();
        this.dedupSeconds = Math.max(10L, dedupSeconds);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            log.warn("飞书长连接已开启，但 app-id/app-secret 未配置，跳过启动。");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        EventDispatcher dispatcher = EventDispatcher.newBuilder(verificationToken, encryptKey)
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        if (isDuplicateMessage(event)) {
                            return;
                        }
                        Map<String, Object> payload = convertToWebhookPayload(event);
                        if (payload == null || payload.isEmpty()) {
                            return;
                        }
                        try {
                            dispatchVerifiedPayload(payload);
                        } catch (Exception ex) {
                            if (isSessionBusy(ex)) {
                                log.info("飞书长连接会话忙，已跳过重复并发消息。reason={}", ex.getMessage());
                                return;
                            }
                            log.warn("飞书长连接事件处理失败，reason={}", ex.getMessage());
                        }
                    }
                })
                .build();

        Client.Builder builder = new Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(true);
        if (StringUtils.hasText(domain)) {
            builder.domain(domain);
        }
        this.wsClient = builder.build();

        worker.submit(() -> {
            log.info("飞书长连接启动中...");
            wsClient.start();
        });
        log.info("飞书长连接已初始化，等待事件中。");
    }

    void dispatchVerifiedPayload(Map<String, Object> payload) {
        webhookRouterService.dispatchTrusted("feishu", payload);
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
        log.info("飞书长连接后台线程已关闭。");
    }

    private Map<String, Object> convertToWebhookPayload(P2MessageReceiveV1 event) {
        if (event == null) {
            return Map.of();
        }
        P2MessageReceiveV1Data data = event.getEvent();
        if (data == null) {
            return Map.of();
        }
        EventSender sender = data.getSender();
        EventMessage message = data.getMessage();
        if (sender == null || message == null) {
            return Map.of();
        }

        UserId senderId = sender.getSenderId();
        String openId = senderId == null ? "" : firstNonBlank(senderId.getOpenId(), senderId.getUserId(), senderId.getUnionId());
        String chatId = nullToEmpty(message.getChatId());
        String chatType = nullToEmpty(message.getChatType());
        String messageType = nullToEmpty(message.getMessageType());
        String content = nullToEmpty(message.getContent());

        if (!StringUtils.hasText(openId) || !StringUtils.hasText(chatId) || !StringUtils.hasText(content)) {
            return Map.of();
        }

        Map<String, Object> senderIdMap = new HashMap<>();
        senderIdMap.put("open_id", openId);
        senderIdMap.put("user_id", senderId == null ? "" : nullToEmpty(senderId.getUserId()));
        senderIdMap.put("union_id", senderId == null ? "" : nullToEmpty(senderId.getUnionId()));

        Map<String, Object> senderMap = new HashMap<>();
        senderMap.put("sender_id", senderIdMap);
        senderMap.put("sender_type", nullToEmpty(sender.getSenderType()));

        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put("message_id", nullToEmpty(message.getMessageId()));
        messageMap.put("chat_id", chatId);
        messageMap.put("chat_type", chatType);
        messageMap.put("message_type", StringUtils.hasText(messageType) ? messageType : "text");
        messageMap.put("content", content);

        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("sender", senderMap);
        eventMap.put("message", messageMap);

        Map<String, Object> payload = new HashMap<>();
        payload.put("event", eventMap);
        return payload;
    }

    private boolean isDuplicateMessage(P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null || event.getEvent().getMessage() == null) {
            return false;
        }
        String messageId = nullToEmpty(event.getEvent().getMessage().getMessageId());
        if (!StringUtils.hasText(messageId)) {
            return false;
        }
        Boolean redisReserved = tryReserveMessageIdInRedis(messageId);
        if (redisReserved != null) {
            return !redisReserved;
        }

        long now = System.currentTimeMillis();
        long expireAt = now + dedupSeconds * 1000L;

        final AtomicBoolean duplicate = new AtomicBoolean(false);
        recentMessageIdCache.compute(messageId, (key, oldExpireAt) -> {
            if (oldExpireAt != null && oldExpireAt > now) {
                duplicate.set(true);
                return oldExpireAt;
            }
            return expireAt;
        });
        pruneExpiredMessageIds(now);
        return duplicate.get();
    }

    private Boolean tryReserveMessageIdInRedis(String messageId) {
        if (!StringUtils.hasText(messageId) || redisTemplate == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().setIfAbsent(
                    MESSAGE_DEDUP_KEY_PREFIX + messageId,
                    "1",
                    dedupSeconds,
                    TimeUnit.SECONDS
            );
        } catch (Exception ex) {
            log.warn("飞书消息 Redis 去重失败，降级本地去重。messageId={}, reason={}", messageId, ex.getMessage());
            return null;
        }
    }

    private void pruneExpiredMessageIds(long now) {
        if (recentMessageIdCache.size() < 1000) {
            return;
        }
        recentMessageIdCache.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean isSessionBusy(Exception ex) {
        return ex instanceof BusinessException businessException
                && businessException.getCode() == 40901;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return Objects.toString(value, "").trim();
    }
}
