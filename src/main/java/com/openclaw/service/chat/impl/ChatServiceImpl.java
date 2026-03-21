package com.openclaw.service.chat.impl;

import com.openclaw.domain.entity.AgentSession;
import com.openclaw.dto.chat.ChatRequest;
import com.openclaw.dto.chat.ChatResponse;
import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.auth.AuthService;
import com.openclaw.service.chat.ChatService;
import com.openclaw.service.chat.LocalSkillFallbackService;
import com.openclaw.service.context.AssembledContext;
import com.openclaw.service.context.ContextAssembler;
import com.openclaw.service.event.MessageEventService;
import com.openclaw.service.guard.ChatGuardService;
import com.openclaw.service.memory.MemoryService;
import com.openclaw.service.prompt.SoulPromptService;
import com.openclaw.service.session.AgentSessionService;
import com.openclaw.service.usage.LlmUsageRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话服务编排层。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final AiProviderService aiProviderService;
    private final SoulPromptService soulPromptService;
    private final AgentSessionService agentSessionService;
    private final MessageEventService messageEventService;
    private final ChatGuardService chatGuardService;
    private final MemoryService memoryService;
    private final ContextAssembler contextAssembler;
    private final OparLoopEngine oparLoopEngine;
    private final SimplifiedOparEngine simplifiedOparEngine;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final AuthService authService;
    private final ChatRoutingStateService chatRoutingStateService;
    private final ChatRoutingPolicyService chatRoutingPolicyService;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final LlmUsageRecordService llmUsageRecordService;
    private final boolean metaGuardEnabled;
    private final int metaGuardRetryTimes;
    private final String configuredAgentMode;
    private final boolean routingAutoUpgradeEnabled;

    public ChatServiceImpl(AiProviderService aiProviderService,
                           SoulPromptService soulPromptService,
                           AgentSessionService agentSessionService,
                           MessageEventService messageEventService,
                           ChatGuardService chatGuardService,
                           MemoryService memoryService,
                           ContextAssembler contextAssembler,
                           OparLoopEngine oparLoopEngine,
                           SimplifiedOparEngine simplifiedOparEngine,
                           ChatResponsePolicyService chatResponsePolicyService,
                           AuthService authService,
                           ChatRoutingStateService chatRoutingStateService,
                           ChatRoutingPolicyService chatRoutingPolicyService,
                           ModelTransportGuardService modelTransportGuardService,
                           ModelCallExecutor modelCallExecutor,
                           ConversationAdvisorSupport conversationAdvisorSupport,
                           LlmUsageRecordService llmUsageRecordService,
                           @Value("${openclaw.chat.meta-guard.enabled:true}") boolean metaGuardEnabled,
                           @Value("${openclaw.chat.meta-guard.retry-times:1}") int metaGuardRetryTimes,
                           @Value("${openclaw.chat.agent-mode:simplified}") String agentMode,
                           @Value("${openclaw.chat.routing.auto-upgrade-enabled:true}") boolean routingAutoUpgradeEnabled) {
        this.aiProviderService = aiProviderService;
        this.soulPromptService = soulPromptService;
        this.agentSessionService = agentSessionService;
        this.messageEventService = messageEventService;
        this.chatGuardService = chatGuardService;
        this.memoryService = memoryService;
        this.contextAssembler = contextAssembler;
        this.oparLoopEngine = oparLoopEngine;
        this.simplifiedOparEngine = simplifiedOparEngine;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.authService = authService;
        this.chatRoutingStateService = chatRoutingStateService;
        this.chatRoutingPolicyService = chatRoutingPolicyService;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.llmUsageRecordService = llmUsageRecordService;
        this.metaGuardEnabled = metaGuardEnabled;
        this.metaGuardRetryTimes = Math.max(0, metaGuardRetryTimes);
        this.configuredAgentMode = normalizeAgentMode(agentMode);
        this.routingAutoUpgradeEnabled = routingAutoUpgradeEnabled;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        chatGuardService.checkRateLimit(request.sessionKey());
        String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
        try {
            ChatContext context = initializeChatContext(request);
            ChatExecutionResult executionResult = runAgentExecution(context);
            String finalAnswer = resolveFinalAnswer(context, executionResult);
            persistChatResult(context, finalAnswer, executionResult);
            return new ChatResponse(
                    context.session().getSessionKey(),
                    finalAnswer,
                    aiProviderService.activeClient().displayName(),
                    System.currentTimeMillis()
            );
        } finally {
            chatGuardService.releaseSessionLock(request.sessionKey(), lockToken);
        }
    }

    @Override
    public SseEmitter stream(ChatRequest request) {
        chatGuardService.checkRateLimit(request.sessionKey());
        String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
        AtomicBoolean lockReleased = new AtomicBoolean(false);

        ChatContext context = initializeChatContext(request);
        ChatExecutionResult executionResult = runAgentExecution(context);
        SseEmitter emitter = new SseEmitter(120_000L);
        StringBuilder fullAnswer = new StringBuilder();

        if (shouldSendImmediateAnswer(context, executionResult)) {
            String answer = resolveFinalAnswer(context, executionResult);
            fullAnswer.append(answer);
            sendToken(emitter, answer);
            persistChatResult(context, answer, executionResult);
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            emitter.complete();
            return emitter;
        }

        AiProviderService.ActiveChatClient reflectClient = aiProviderService.activeClient();
        String reflectPrompt = oparLoopEngine.renderReflectPrompt(context.assembled(), executionResult.plan(), executionResult.action());
        final org.springframework.ai.chat.model.ChatResponse[] latestStreamResponse = new org.springframework.ai.chat.model.ChatResponse[1];
        Disposable disposable = conversationAdvisorSupport.apply(
                        reflectClient.chatClient().prompt()
                                .system(context.systemPrompt())
                                .user(reflectPrompt),
                        context.assembled().sessionKey(),
                        context.assembled().userId())
                .stream()
                .chatClientResponse()
                .doOnNext(response -> {
                    if (response != null && response.chatResponse() != null) {
                        latestStreamResponse[0] = response.chatResponse();
                        String token = ModelCallExecutor.extractText(response.chatResponse());
                        if (StringUtils.hasText(token)) {
                            fullAnswer.append(token);
                            sendToken(emitter, token);
                        }
                    }
                })
                .doOnComplete(() -> {
                    if (latestStreamResponse[0] != null) {
                        llmUsageRecordService.recordChatResponse(
                                new LlmUsageRecordService.ChatResponseContext(
                                        context.requestId(),
                                        context.assembled().sessionKey(),
                                        context.assembled().channel(),
                                        context.assembled().userId(),
                                        reflectClient.providerId(),
                                        reflectClient.model(),
                                        "stream-final-answer"
                                ),
                                latestStreamResponse[0]
                        );
                    }
                    String answer = fullAnswer.toString();
                    if (chatResponsePolicyService.looksLikeMetaRefusal(answer)
                            || chatResponsePolicyService.looksLikeProjectAccessRefusal(answer)
                            || chatResponsePolicyService.looksLikeToolFailureRefusal(answer)) {
                        try {
                            String repaired = runFinalAnswerWithGuard(context, executionResult.plan(), executionResult.action());
                            if (StringUtils.hasText(repaired) && !repaired.equals(answer)) {
                                fullAnswer.setLength(0);
                                fullAnswer.append(repaired);
                                sendToken(emitter, "\n\n【自动修正回答】\n" + repaired);
                            }
                        } catch (Exception ex) {
                            log.warn("流式回答自动修正失败，sessionKey={}, reason={}", context.assembled().sessionKey(), ex.getMessage());
                        }
                    }
                    persistChatResult(context, fullAnswer.toString(), executionResult);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    emitter.complete();
                })
                .doOnError(ex -> {
                    modelTransportGuardService.markFailure(reflectClient.providerId(), ex);
                    String fallback = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
                    if (!StringUtils.hasText(fallback)) {
                        fallback = fallbackAnswer(modelTransportGuardService.disabledModelReason(reflectClient), context.assembled());
                    }
                    fullAnswer.setLength(0);
                    fullAnswer.append(fallback);
                    sendToken(emitter, fallback);
                    persistChatResult(context, fullAnswer.toString(), executionResult);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    emitter.complete();
                })
                .subscribe();

        emitter.onCompletion(() -> {
            disposable.dispose();
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
        });
        emitter.onTimeout(() -> {
            disposable.dispose();
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            emitter.complete();
        });
        return emitter;
    }

    private ChatContext initializeChatContext(ChatRequest request) {
        String channel = StringUtils.hasText(request.channel()) ? request.channel() : "api";
        AgentSession session = agentSessionService.getOrCreate(request.sessionKey(), channel, request.userId());
        String requestId = generateRequestId();
        String roleCode = authService.resolveRoleByUserId(request.userId());
        String effectiveDefaultMode = chatRoutingStateService.resolveDefaultMode(configuredAgentMode);
        boolean effectiveAutoUpgrade = chatRoutingStateService.resolveAutoUpgrade(routingAutoUpgradeEnabled);
        ChatRoutingPolicyService.RoutingDecision routingDecision = chatRoutingPolicyService.decide(
                request.message(),
                roleCode,
                effectiveDefaultMode,
                effectiveAutoUpgrade
        );
        if (routingDecision == null) {
            routingDecision = new ChatRoutingPolicyService.RoutingDecision(
                    request.message(),
                    effectiveDefaultMode,
                    false,
                    false,
                    "路由策略未返回结果，回退到当前默认链路。"
            );
        }
        String systemPrompt = soulPromptService.buildSystemPrompt(channel, request.userId());
        AssembledContext assembled = contextAssembler.assemble(
                session.getSessionKey(),
                channel,
                request.userId(),
                routingDecision.effectiveQuestion()
        );
        AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
        return new ChatContext(
                session,
                channel,
                request.userId(),
                roleCode,
                request.message(),
                routingDecision.effectiveQuestion(),
                requestId,
                systemPrompt,
                assembled,
                activeClient,
                routingDecision.executionMode(),
                routingDecision.reason()
        );
    }

    private ChatExecutionResult runAgentExecution(ChatContext context) {
        return useSimplifiedMode(context.executionMode())
                ? simplifiedOparEngine.run(context.activeClient(), context.systemPrompt(), context.assembled(), context.requestId(), this::fallbackAnswer)
                : oparLoopEngine.runLoop(context.activeClient(), context.systemPrompt(), context.assembled(), context.requestId(), this::fallbackAnswer);
    }

    private String resolveFinalAnswer(ChatContext context, ChatExecutionResult executionResult) {
        if (StringUtils.hasText(executionResult.reflect())) {
            return executionResult.reflect();
        }
        if (!executionResult.modelEnabled()) {
            return fallbackAnswer(modelTransportGuardService.disabledModelReason(context.activeClient()), context.assembled());
        }
        try {
            return runFinalAnswerWithGuard(context, executionResult.plan(), executionResult.action());
        } catch (Exception ex) {
            log.warn("最终回答生成失败，sessionKey={}, reason={}", context.assembled().sessionKey(), ex.getMessage());
            String partial = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
            if (StringUtils.hasText(partial)) {
                return partial;
            }
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
            return fallbackAnswer(modelTransportGuardService.disabledModelReason(context.activeClient()), context.assembled());
        }
    }

    private String runFinalAnswerWithGuard(ChatContext context,
                                           String plan,
                                           String action) throws Exception {
        String prompt = oparLoopEngine.renderReflectPrompt(context.assembled(), plan, action);
        AiProviderService.ActiveChatClient currentClient = aiProviderService.activeClient();
        ModelCallExecutor.ModelCallResult<String> answerResult = modelCallExecutor.executeChat(
                currentClient,
                "final-answer",
                new ModelCallExecutor.ChatRequestContext(
                        context.requestId(),
                        context.assembled().sessionKey(),
                        context.assembled().channel(),
                        context.assembled().userId()
                ),
                true,
                client -> {
                    var response = conversationAdvisorSupport.apply(
                                    client.chatClient().prompt()
                                            .system(context.systemPrompt())
                                            .user(prompt),
                                    context.assembled().sessionKey(),
                                    context.assembled().userId())
                            .call()
                            .chatResponse();
                    return new ModelCallExecutor.ChatOperationResult<>(
                            ModelCallExecutor.extractText(response),
                            response
                    );
                }
        );
        String answer = answerResult.value();
        currentClient = answerResult.client();

        if (!metaGuardEnabled || !chatResponsePolicyService.looksLikeMetaRefusal(answer)) {
            return normalizeFinalAnswer(context, answer);
        }

        log.warn("检测到身份/阶段元话术，触发重试。sessionKey={}", context.assembled().sessionKey());
        String repaired = answer;
        for (int i = 0; i < metaGuardRetryTimes; i++) {
            String retryPrompt = oparLoopEngine.renderMetaRepairPrompt(context.assembled(), plan, action, repaired);
            ModelCallExecutor.ModelCallResult<String> retryResult = modelCallExecutor.executeChat(
                    currentClient,
                    "meta-repair",
                    new ModelCallExecutor.ChatRequestContext(
                            context.requestId(),
                            context.assembled().sessionKey(),
                            context.assembled().channel(),
                            context.assembled().userId()
                    ),
                    true,
                    client -> {
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(context.systemPrompt())
                                                .user(retryPrompt),
                                        context.assembled().sessionKey(),
                                        context.assembled().userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            repaired = retryResult.value();
            currentClient = retryResult.client();
            if (!chatResponsePolicyService.looksLikeMetaRefusal(repaired)) {
                return normalizeFinalAnswer(context, repaired);
            }
        }

        LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
        if (localResult != null) {
            return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
        }
        return fallbackAnswer("模型输出了身份/阶段元话术，已降级输出。", context.assembled());
    }

    private String normalizeFinalAnswer(ChatContext context, String answer) {
        if (!StringUtils.hasText(answer)) {
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
            return fallbackAnswer("模型返回空回答。", context.assembled());
        }
        if (chatResponsePolicyService.looksLikeProjectAccessRefusal(answer)
                || chatResponsePolicyService.looksLikeToolFailureRefusal(answer)) {
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
        }
        return answer;
    }

    private LocalSkillFallbackService.LocalSkillResult tryLocalFallbackOrNull(ChatContext context) {
        return oparLoopEngine.tryLocalFallbackResult(context.assembled().question());
    }

    private void persistChatResult(ChatContext context,
                                   String assistantMessage,
                                   ChatExecutionResult executionResult) {
        agentSessionService.persistConversation(
                context.session(),
                context.effectiveUserMessage(),
                assistantMessage,
                soulPromptService.soulVersion()
        );
        memoryService.storeConversationTurn(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.effectiveUserMessage(),
                normalizeAssistantForMemory(assistantMessage)
        );
        messageEventService.recordTurn(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.effectiveUserMessage(),
                "[REFLECT] " + truncate(normalizeAssistantForMemory(assistantMessage), 1600),
                "CHAT",
                context.requestId()
        );
        messageEventService.recordSingle(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                "SYSTEM",
                "OPAR",
                "ROUTING=mode=%s, role=%s, reason=%s".formatted(
                        context.executionMode(),
                        context.roleCode(),
                        truncate(context.routingReason(), 300)
                ),
                context.requestId()
        );
        messageEventService.recordSingle(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                "SYSTEM",
                "OPAR",
                "PLAN=" + truncate(executionResult.plan(), 1200),
                context.requestId()
        );
        messageEventService.recordSingle(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                "SYSTEM",
                "OPAR",
                "ACT=" + truncate(executionResult.action(), 1200),
                context.requestId()
        );
    }

    private boolean shouldSendImmediateAnswer(ChatContext context, ChatExecutionResult executionResult) {
        return !executionResult.modelEnabled()
                || StringUtils.hasText(executionResult.reflect())
                || useSimplifiedMode(context.executionMode());
    }

    private boolean useSimplifiedMode(String executionMode) {
        return "simplified".equals(normalizeAgentMode(executionMode));
    }

    private String normalizeAgentMode(String rawAgentMode) {
        if (!StringUtils.hasText(rawAgentMode)) {
            return "simplified";
        }
        String normalized = rawAgentMode.trim().toLowerCase(Locale.ROOT);
        if ("opar".equals(normalized)) {
            return "opar";
        }
        return "simplified";
    }

    private String fallbackAnswer(String reason, AssembledContext context) {
        return chatResponsePolicyService.buildUserFacingFailureReply(reason, context.question());
    }

    private void sendToken(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event().name("token").data(token));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String normalizeAssistantForMemory(String answer) {
        if (!StringUtils.hasText(answer)) {
            return "";
        }
        if (answer.startsWith("当前已进入降级模式")) {
            return "系统处于降级模式，返回了兜底响应。";
        }
        return answer;
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void releaseSessionLockOnce(String sessionKey, String token, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            chatGuardService.releaseSessionLock(sessionKey, token);
        }
    }

    private String truncate(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private record ChatContext(AgentSession session,
                               String channel,
                               String userId,
                               String roleCode,
                               String userMessage,
                               String effectiveUserMessage,
                               String requestId,
                               String systemPrompt,
                               AssembledContext assembled,
                               AiProviderService.ActiveChatClient activeClient,
                               String executionMode,
                               String routingReason) {
    }
}
