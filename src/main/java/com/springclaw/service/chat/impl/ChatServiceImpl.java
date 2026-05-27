package com.springclaw.service.chat.impl;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ToolOrchestrator toolOrchestrator;
    private final boolean modelLedStreamingEnabled;

    @Autowired
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
                           MetaGuardExecutor metaGuardExecutor,
                           ToolOrchestrator toolOrchestrator,
                           @Value("${springclaw.chat.model-led-streaming-enabled:false}") boolean modelLedStreamingEnabled) {
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
        this.toolOrchestrator = toolOrchestrator;
        this.modelLedStreamingEnabled = modelLedStreamingEnabled;
    }

    ChatServiceImpl(AiProviderService aiProviderService,
                    ChatGuardService chatGuardService,
                    OparLoopEngine oparLoopEngine,
                    SimplifiedOparEngine simplifiedOparEngine,
                    ChatResponsePolicyService chatResponsePolicyService,
                    ModelTransportGuardService modelTransportGuardService,
                    LlmUsageRecordService llmUsageRecordService,
                    ConversationAdvisorSupport conversationAdvisorSupport,
                    ChatContextFactory chatContextFactory,
                    ChatResultPersister chatResultPersister,
                    MetaGuardExecutor metaGuardExecutor,
                    ToolOrchestrator toolOrchestrator) {
        this(aiProviderService,
                chatGuardService,
                oparLoopEngine,
                simplifiedOparEngine,
                chatResponsePolicyService,
                modelTransportGuardService,
                llmUsageRecordService,
                conversationAdvisorSupport,
                chatContextFactory,
                chatResultPersister,
                metaGuardExecutor,
                toolOrchestrator,
                false);
    }

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
        this(aiProviderService,
                chatGuardService,
                oparLoopEngine,
                simplifiedOparEngine,
                chatResponsePolicyService,
                modelTransportGuardService,
                llmUsageRecordService,
                conversationAdvisorSupport,
                chatContextFactory,
                chatResultPersister,
                metaGuardExecutor,
                null,
                false);
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
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();

        emitter.onCompletion(() -> {
            Disposable disposable = disposableRef.get();
            if (disposable != null) {
                disposable.dispose();
            }
            releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
        });
        emitter.onTimeout(() -> {
            Disposable disposable = disposableRef.get();
            if (disposable != null) {
                disposable.dispose();
            }
            releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
            emitter.complete();
        });
        CompletableFuture.runAsync(() -> executeStream(request, lockToken, lockReleased, emitter, disposableRef));
        return emitter;
    }

    private void executeStream(ChatRequest request,
                               String lockToken,
                               AtomicBoolean lockReleased,
                               SseEmitter emitter,
                               AtomicReference<Disposable> disposableRef) {
        try {
            sendStatus(emitter, "正在组织上下文");
            ChatContext context = chatContextFactory.build(request, true);
            sendMeta(emitter, context);
            if (shouldUseModelLedStreaming(context)) {
                streamModelLedAnswer(context, lockToken, lockReleased, emitter, disposableRef);
                return;
            }
            sendStatus(emitter, "正在执行 Agent");
            ChatExecutionResult executionResult = runAgentExecution(context);

            if (shouldSendImmediateAnswer(context, executionResult)) {
                streamImmediateAnswer(context, executionResult, emitter);
                releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                completeEmitter(emitter);
                return;
            }

            streamReflectAnswer(context, executionResult, lockToken, lockReleased, emitter, disposableRef);
        } catch (Exception ex) {
            log.warn("流式聊天启动失败，sessionKey={}, reason={}", request.sessionKey(), ex.getMessage());
            sendError(emitter, ex.getMessage());
            releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
            completeEmitter(emitter);
        }
    }

    private void streamImmediateAnswer(ChatContext context,
                                       ChatExecutionResult executionResult,
                                       SseEmitter emitter) {
        String answer = resolveFinalAnswer(context, executionResult);
        sendAnswerChunks(emitter, answer);
        chatResultPersister.persist(context, answer, executionResult);
    }

    private void streamModelLedAnswer(ChatContext context,
                                      String lockToken,
                                      AtomicBoolean lockReleased,
                                      SseEmitter emitter,
                                      AtomicReference<Disposable> disposableRef) {
        sendStatus(emitter, "正在调用模型并实时输出");
        StringBuilder fullAnswer = new StringBuilder();
        AiProviderService.ActiveChatClient streamClient = context.activeClient();
        final org.springframework.ai.chat.model.ChatResponse[] latestStreamResponse = new org.springframework.ai.chat.model.ChatResponse[1];
        AtomicReference<ToolExecutionContextHolder.Scope> toolScopeRef = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);

        try {
            Object[] tools = toolOrchestrator == null
                    ? new Object[0]
                    : toolOrchestrator.selectAgentTools(context.channel(), context.userId());
            var requestSpec = streamClient.chatClient().prompt()
                    .system(context.systemPrompt())
                    .user(renderModelLedPrompt(context.assembled()));
            if (DeepSeekChatCompatibility.supportsNativeToolCalling(streamClient) && tools != null && tools.length > 0) {
                requestSpec = requestSpec.tools(tools);
            }
            ToolExecutionContext toolContext = new ToolExecutionContext(
                    context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.requestId(),
                    "ACT-STREAM"
            );
            Disposable disposable = conversationAdvisorSupport.apply(
                            requestSpec,
                            context.assembled().sessionKey(),
                            context.assembled().userId())
                    .stream()
                    .chatClientResponse()
                    .doOnSubscribe(subscription -> toolScopeRef.set(ToolExecutionContextHolder.open(toolContext)))
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
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        closeToolScope(toolScopeRef);
                        if (latestStreamResponse[0] != null) {
                            llmUsageRecordService.recordChatResponse(
                                    new LlmUsageRecordService.ChatResponseContext(
                                            context.requestId(),
                                            context.assembled().sessionKey(),
                                            context.assembled().channel(),
                                            context.assembled().userId(),
                                            streamClient.providerId(),
                                            streamClient.model(),
                                            "stream-agent-answer"
                                    ),
                                    latestStreamResponse[0]
                            );
                        }
                        String answer = fullAnswer.toString();
                        if (!StringUtils.hasText(answer)) {
                            streamBlockingFallback(context, new IllegalStateException("模型未返回可见内容"),
                                    lockToken, lockReleased, emitter);
                            return;
                        }
                        chatResultPersister.persist(context, answer, new ChatExecutionResult(
                                context.assembled().observePrompt(),
                                "STREAM_AGENT: 模型直接流式生成，并可自行调用工具。",
                                "使用 responseMode=agent 的 model-led streaming 分支。",
                                answer,
                                true
                        ));
                        releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                        completeEmitter(emitter);
                    })
                    .doOnError(ex -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        closeToolScope(toolScopeRef);
                        modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                        if (streamInterruptedPartialAnswer(context, fullAnswer, ex, lockToken, lockReleased, emitter)) {
                            return;
                        }
                        streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
                    })
                    .doFinally(signalType -> closeToolScope(toolScopeRef))
                    .subscribe();
            disposableRef.set(disposable);
        } catch (Exception ex) {
            if (finished.compareAndSet(false, true)) {
                closeToolScope(toolScopeRef);
                modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                if (streamInterruptedPartialAnswer(context, fullAnswer, ex, lockToken, lockReleased, emitter)) {
                    return;
                }
                streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
            }
        }
    }

    private boolean streamInterruptedPartialAnswer(ChatContext context,
                                                  StringBuilder fullAnswer,
                                                  Throwable streamFailure,
                                                  String lockToken,
                                                  AtomicBoolean lockReleased,
                                                  SseEmitter emitter) {
        String partial = fullAnswer == null ? "" : fullAnswer.toString().trim();
        if (!StringUtils.hasText(partial)) {
            return false;
        }
        String reason = chatResponsePolicyService.simplifyFailureReason(streamFailure == null ? "" : streamFailure.getMessage());
        String notice = "\n\n生成中断：" + reason + " 已保留上面已经收到的内容；如果要完整回答，请点“重试上一条”。";
        String answer = partial + notice;
        sendStatus(emitter, "模型流式连接中断，已保留部分内容");
        sendToken(emitter, notice);
        chatResultPersister.persist(context, answer, new ChatExecutionResult(
                context.assembled().observePrompt(),
                "STREAM_AGENT_INTERRUPTED: 模型流式输出中途断开。",
                "已保留部分模型输出，不再把失败兜底回答拼接到半截回答后。reason=" + safe(streamFailure == null ? "" : streamFailure.getMessage()),
                answer,
                false
        ));
        releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
        completeEmitter(emitter);
        return true;
    }

    private void streamBlockingFallback(ChatContext context,
                                        Throwable streamFailure,
                                        String lockToken,
                                        AtomicBoolean lockReleased,
                                        SseEmitter emitter) {
        try {
            sendStatus(emitter, "流式通道异常，正在降级整理结果");
            ChatExecutionResult fallbackResult = runAgentExecution(context);
            String answer = resolveFinalAnswer(context, fallbackResult);
            if (!StringUtils.hasText(answer)) {
                answer = metaGuardExecutor.fallbackAnswer(
                        streamFailure == null ? "模型未返回内容" : streamFailure.getMessage(),
                        context.assembled()
                );
            }
            sendAnswerChunks(emitter, answer);
            chatResultPersister.persist(context, answer, fallbackResult);
        } catch (Exception fallbackEx) {
            log.warn("流式降级回答失败，sessionKey={}, reason={}", context.session().getSessionKey(), fallbackEx.getMessage());
            sendError(emitter, fallbackEx.getMessage());
        } finally {
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            completeEmitter(emitter);
        }
    }

    boolean shouldUseModelLedStreaming(ChatContext context) {
        return context != null
                && modelLedStreamingEnabled
                && "agent".equals(normalizeResponseMode(context.responseMode()))
                && !"control-plane".equalsIgnoreCase(safe(context.intent()))
                && !"local-files".equalsIgnoreCase(safe(context.intent()))
                && useSimplifiedMode(context.executionMode())
                && modelTransportGuardService.isModelCallEnabled(context.activeClient());
    }

    private String renderModelLedPrompt(AssembledContext assembled) {
        return """
                用户问题：
                %s

                请像成熟 Agent 一样完成这次请求：
                - 需要查项目、文件、网页、天气、脚本 skill 时，优先调用可用工具拿证据。
                - 工具结果只是证据，最终回答要由你组织成完整中文回复。
                - 不要输出内部阶段名、route、OPAR、兜底、本地工具等实现词。
                - 回答要有结论、依据和下一步；如果信息不足，明确说明还缺什么。
                """.formatted(safe(assembled == null ? null : assembled.question()));
    }

    private void streamReflectAnswer(ChatContext context,
                                     ChatExecutionResult executionResult,
                                     String lockToken,
                                     AtomicBoolean lockReleased,
                                     SseEmitter emitter,
                                     AtomicReference<Disposable> disposableRef) {
        StringBuilder fullAnswer = new StringBuilder();
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
                    completeEmitter(emitter);
                })
                .doOnError(ex -> {
                    modelTransportGuardService.markFailure(reflectClient.providerId(), ex);
                    String fallback = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
                    if (!StringUtils.hasText(fallback)) {
                        fallback = metaGuardExecutor.fallbackAnswer(modelTransportGuardService.disabledModelReason(reflectClient), context.assembled());
                    }
                    fullAnswer.setLength(0);
                    fullAnswer.append(fallback);
                    sendAnswerChunks(emitter, fallback);
                    chatResultPersister.persist(context, fullAnswer.toString(), executionResult);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    completeEmitter(emitter);
                })
                .subscribe();
        disposableRef.set(disposable);
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

    private String normalizeResponseMode(String rawResponseMode) {
        if (!StringUtils.hasText(rawResponseMode)) {
            return "agent";
        }
        return switch (rawResponseMode.trim().toLowerCase(Locale.ROOT)) {
            case "fast", "deep", "tool" -> rawResponseMode.trim().toLowerCase(Locale.ROOT);
            default -> "agent";
        };
    }

    private void closeToolScope(AtomicReference<ToolExecutionContextHolder.Scope> scopeRef) {
        ToolExecutionContextHolder.Scope scope = scopeRef.getAndSet(null);
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Exception ignored) {
            // ThreadLocal cleanup must never break response completion.
        }
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private void sendToken(SseEmitter emitter, String token) {
        sendEvent(emitter, "token", token);
    }

    private void sendStatus(SseEmitter emitter, String status) {
        sendEvent(emitter, "status", status);
    }

    private void sendMeta(SseEmitter emitter, ChatContext context) {
        if (context == null) {
            return;
        }
        String payload = """
                {"requestId":"%s","responseMode":"%s","executionMode":"%s","intent":"%s","routingReason":"%s"}
                """.formatted(
                jsonEscape(context.requestId()),
                jsonEscape(normalizeResponseMode(context.responseMode())),
                jsonEscape(context.executionMode()),
                jsonEscape(context.intent()),
                jsonEscape(context.routingReason())
        ).trim();
        sendEvent(emitter, "meta", payload);
    }

    private void sendError(SseEmitter emitter, String error) {
        sendEvent(emitter, "error", chatResponsePolicyService.simplifyFailureReason(error));
    }

    private void completeEmitter(SseEmitter emitter) {
        sendEvent(emitter, "done", "done");
        emitter.complete();
    }

    private void sendAnswerChunks(SseEmitter emitter, String answer) {
        String text = answer == null ? "" : answer;
        if (!StringUtils.hasText(text)) {
            return;
        }
        int chunkSize = 48;
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(text.length(), start + chunkSize);
            sendToken(emitter, text.substring(start, end));
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data == null ? "" : data));
        } catch (IOException e) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Client disconnected or emitter was already completed.
            }
        } catch (IllegalStateException ignored) {
            // Client disconnected or emitter was already completed.
        }
    }

    private String jsonEscape(String text) {
        String value = text == null ? "" : text;
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
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
