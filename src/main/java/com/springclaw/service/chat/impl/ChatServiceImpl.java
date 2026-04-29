package com.springclaw.service.chat.impl;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话服务编排层。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final AiProviderService aiProviderService;
    private final ChatGuardService chatGuardService;
    private final OparLoopEngine oparLoopEngine;
    private final SimplifiedOparEngine simplifiedOparEngine;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final ModelTransportGuardService modelTransportGuardService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ChatContextFactory chatContextFactory;
    private final ChatResultPersister chatResultPersister;
    private final MetaGuardExecutor metaGuardExecutor;

    public ChatServiceImpl(AiProviderService aiProviderService,
                           ChatGuardService chatGuardService,
                           OparLoopEngine oparLoopEngine,
                           SimplifiedOparEngine simplifiedOparEngine,
                           ChatResponsePolicyService chatResponsePolicyService,
                           ModelTransportGuardService modelTransportGuardService,
                           LlmUsageRecordService llmUsageRecordService,
                           ConversationAdvisorSupport conversationAdvisorSupport,
                           ChatContextFactory chatContextFactory,
                           ChatResultPersister chatResultPersister,
                           MetaGuardExecutor metaGuardExecutor) {
        this.aiProviderService = aiProviderService;
        this.chatGuardService = chatGuardService;
        this.oparLoopEngine = oparLoopEngine;
        this.simplifiedOparEngine = simplifiedOparEngine;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.modelTransportGuardService = modelTransportGuardService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.chatContextFactory = chatContextFactory;
        this.chatResultPersister = chatResultPersister;
        this.metaGuardExecutor = metaGuardExecutor;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        TaskChatExecutionResult result = executeInternal(request, true, true);
        return new ChatResponse(
                result.sessionKey(),
                result.answer(),
                aiProviderService.activeClient().displayName(),
                System.currentTimeMillis()
        );
    }

    @Override
    public SseEmitter stream(ChatRequest request) {
        chatGuardService.checkRateLimit(request.sessionKey());
        String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
        AtomicBoolean lockReleased = new AtomicBoolean(false);

        ChatContext context = chatContextFactory.build(request, true);
        ChatExecutionResult executionResult = runAgentExecution(context);
        SseEmitter emitter = new SseEmitter(120_000L);
        StringBuilder fullAnswer = new StringBuilder();

        if (shouldSendImmediateAnswer(context, executionResult)) {
            String answer = resolveFinalAnswer(context, executionResult);
            fullAnswer.append(answer);
            sendToken(emitter, answer);
            chatResultPersister.persist(context, answer, executionResult);
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
                            String repaired = metaGuardExecutor.execute(context, executionResult.plan(), executionResult.action());
                            if (StringUtils.hasText(repaired) && !repaired.equals(answer)) {
                                fullAnswer.setLength(0);
                                fullAnswer.append(repaired);
                                sendToken(emitter, "\n\n【自动修正回答】\n" + repaired);
                            }
                        } catch (Exception ex) {
                            log.warn("流式回答自动修正失败，sessionKey={}, reason={}", context.assembled().sessionKey(), ex.getMessage());
                        }
                    }
                    chatResultPersister.persist(context, fullAnswer.toString(), executionResult);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    emitter.complete();
                })
                .doOnError(ex -> {
                    modelTransportGuardService.markFailure(reflectClient.providerId(), ex);
                    String fallback = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
                    if (!StringUtils.hasText(fallback)) {
                        fallback = metaGuardExecutor.fallbackAnswer(modelTransportGuardService.disabledModelReason(reflectClient), context.assembled());
                    }
                    fullAnswer.setLength(0);
                    fullAnswer.append(fallback);
                    sendToken(emitter, fallback);
                    chatResultPersister.persist(context, fullAnswer.toString(), executionResult);
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

    public TaskChatExecutionResult executeTaskMessage(ChatRequest request, boolean persistResult) {
        return executeInternal(request, false, persistResult);
    }

    private TaskChatExecutionResult executeInternal(ChatRequest request,
                                                    boolean enforceRateLimit,
                                                    boolean persistResult) {
        if (enforceRateLimit) {
            chatGuardService.checkRateLimit(request.sessionKey());
        }
        String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
        try {
            ChatContext context = chatContextFactory.build(request, persistResult);
            ChatExecutionResult executionResult = runAgentExecution(context);
            String finalAnswer = resolveFinalAnswer(context, executionResult);
            if (persistResult) {
                chatResultPersister.persist(context, finalAnswer, executionResult);
            }
            return new TaskChatExecutionResult(
                    context.session().getSessionKey(),
                    finalAnswer,
                    context.requestId(),
                    context.executionMode(),
                    context.routingReason()
            );
        } finally {
            chatGuardService.releaseSessionLock(request.sessionKey(), lockToken);
        }
    }

    private ChatExecutionResult runAgentExecution(ChatContext context) {
        return useSimplifiedMode(context.executionMode())
                ? simplifiedOparEngine.run(context.activeClient(), context.systemPrompt(), context.assembled(), context.requestId(), metaGuardExecutor::fallbackAnswer)
                : oparLoopEngine.runLoop(context.activeClient(), context.systemPrompt(), context.assembled(), context.requestId(), metaGuardExecutor::fallbackAnswer);
    }

    private String resolveFinalAnswer(ChatContext context, ChatExecutionResult executionResult) {
        if (StringUtils.hasText(executionResult.reflect())) {
            return executionResult.reflect();
        }
        if (!executionResult.modelEnabled()) {
            return metaGuardExecutor.fallbackAnswer(modelTransportGuardService.disabledModelReason(context.activeClient()), context.assembled());
        }
        try {
            return metaGuardExecutor.execute(context, executionResult.plan(), executionResult.action());
        } catch (Exception ex) {
            log.warn("最终回答生成失败，sessionKey={}, reason={}", context.assembled().sessionKey(), ex.getMessage());
            String partial = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
            if (StringUtils.hasText(partial)) {
                return partial;
            }
            LocalSkillFallbackService.LocalSkillResult localResult = oparLoopEngine.tryLocalFallbackResult(context.assembled().question());
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
            return metaGuardExecutor.fallbackAnswer(modelTransportGuardService.disabledModelReason(context.activeClient()), context.assembled());
        }
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

    private void sendToken(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event().name("token").data(token));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void releaseSessionLockOnce(String sessionKey, String token, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            chatGuardService.releaseSessionLock(sessionKey, token);
        }
    }

    public record TaskChatExecutionResult(String sessionKey,
                                          String answer,
                                          String requestId,
                                          String executionMode,
                                          String routingReason) {
    }
}
