package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 最短路径引擎：处理普通聊天（general intent），不挂工具，直接调模型流式输出。
 * 从 ChatServiceImpl.streamBasicModelAnswer 提取，实现 AgentEngine 接口。
 */
@Service
public class BasicStreamEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(BasicStreamEngine.class);

    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final OparLoopEngine oparLoopEngine;
    private final SseEventBridge sseEventBridge;
    private final ChatResultPersister chatResultPersister;
    private final ChatGuardService chatGuardService;
    private final RunLifecycleObserver lifecycleObserver;
    private final boolean basicStreamingEnabled;

    public BasicStreamEngine(ModelCallExecutor modelCallExecutor,
                             ConversationAdvisorSupport conversationAdvisorSupport,
                             ModelTransportGuardService modelTransportGuardService,
                             ChatResponsePolicyService chatResponsePolicyService,
                             LlmUsageRecordService llmUsageRecordService,
                             OparLoopEngine oparLoopEngine,
                             SseEventBridge sseEventBridge,
                             ChatResultPersister chatResultPersister,
                             ChatGuardService chatGuardService,
                             RunLifecycleObserver lifecycleObserver,
                             @Value("${springclaw.chat.basic-streaming-enabled:true}") boolean basicStreamingEnabled) {
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.modelTransportGuardService = modelTransportGuardService;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.oparLoopEngine = oparLoopEngine;
        this.sseEventBridge = sseEventBridge;
        this.chatResultPersister = chatResultPersister;
        this.chatGuardService = chatGuardService;
        this.lifecycleObserver = lifecycleObserver;
        this.basicStreamingEnabled = basicStreamingEnabled;
    }

    @Override
    public String name() {
        return "basic-stream";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.SINGLE_TURN;
    }

    @Override
    public int priority() {
        return 1; // 最高优先级：普通聊天优先走最短路径
    }

    @Override
    public boolean supports(ChatContext ctx) {
        if (ctx == null) return false;
        String responseMode = normalizeResponseMode(ctx.responseMode());
        return basicStreamingEnabled
                && useSimplifiedMode(ctx.executionMode())
                && ("agent".equals(responseMode) || "fast".equals(responseMode))
                && isGeneralIntent(ctx)
                && modelTransportGuardService.isModelCallEnabled(ctx.activeClient());
    }

    /**
     * SSE 流式执行：模型直接流式输出，不含工具调用。
     * 成功时自行持久化并完成 SSE；部分内容中断时保留部分内容；
     * 完全失败时委托给 fallbackHandler 进行降级处理。
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        long startedAt = System.currentTimeMillis();
        sseEventBridge.sendTrace(emitter, context, "选择能力", "model", "success",
                "普通聊天命中最短路径：不挂载工具，不进入多步规划。", 0L);
        sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "started",
                context.activeClient().displayName(), 0L);
        sseEventBridge.sendStatus(emitter, "正在调用模型并实时输出");

        StringBuilder fullAnswer = new StringBuilder();
        AiProviderService.ActiveChatClient streamClient = context.activeClient();
        final ChatResponse[] latestStreamResponse = new ChatResponse[1];
        AtomicBoolean finished = new AtomicBoolean(false);

        try {
            Disposable disposable = conversationAdvisorSupport.apply(
                            streamClient.chatClient().prompt()
                                    .system(context.systemPrompt())
                                    .user(renderBasicChatPrompt(context)),
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
                                sseEventBridge.sendToken(emitter, token);
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        if (latestStreamResponse[0] != null) {
                            llmUsageRecordService.recordChatResponse(
                                    new LlmUsageRecordService.ChatResponseContext(
                                            context.requestId(),
                                            context.assembled().sessionKey(),
                                            context.assembled().channel(),
                                            context.assembled().userId(),
                                            streamClient.providerId(),
                                            streamClient.model(),
                                            "stream-basic-answer"
                                    ),
                                    latestStreamResponse[0]
                            );
                        }
                        String answer = fullAnswer.toString();
                        if (!StringUtils.hasText(answer)) {
                            fallbackHandler.handle(context,
                                    new IllegalStateException("模型未返回可见内容"),
                                    emitter, lockToken, lockReleased);
                            return;
                        }
                        ChatExecutionResult basicResult = new ChatExecutionResult(
                                context.assembled().observePrompt(),
                                "BASIC_STREAM: 普通聊天最短路径。",
                                "未挂载工具，未进入多步规划。",
                                answer,
                                true
                        );
                        chatResultPersister.persist(context, answer, basicResult, ChatPersistenceIntent.TERMINAL_RESULT);
                        reportResult(context, basicResult, answer);
                        sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "success",
                                streamClient.displayName(), System.currentTimeMillis() - startedAt);
                        sseEventBridge.sendTrace(emitter, context, "完成", "final", "success",
                                "已生成最终回答。", System.currentTimeMillis() - startedAt);
                        releaseLock(emitter, context, lockToken, lockReleased);
                    })
                    .doOnError(ex -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                        sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "failed",
                                chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                                System.currentTimeMillis() - startedAt);
                        if (handlePartialAnswer(context, fullAnswer, ex, emitter, lockToken, lockReleased)) {
                            return;
                        }
                        sseEventBridge.sendTrace(emitter, context, "降级处理", "fallback", "started",
                                "流式最短路径失败，转入 Agent 兜底链路。", 0L);
                        fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
                    })
                    .subscribe();
            disposableRef.set(disposable);
            return disposable;
        } catch (Exception ex) {
            if (finished.compareAndSet(false, true)) {
                modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "failed",
                        chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                        System.currentTimeMillis() - startedAt);
                if (handlePartialAnswer(context, fullAnswer, ex, emitter, lockToken, lockReleased)) {
                    return null;
                }
                sseEventBridge.sendTrace(emitter, context, "降级处理", "fallback", "started",
                        "流式最短路径启动失败，转入 Agent 兜底链路。", 0L);
                fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
            }
            return null;
        }
    }

    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        try {
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    ctx.activeClient(),
                    "basic-answer",
                    new ModelCallExecutor.ChatRequestContext(
                            ctx.requestId(),
                            ctx.assembled().sessionKey(),
                            ctx.assembled().channel(),
                            ctx.assembled().userId()
                    ),
                    true,
                    client -> {
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(ctx.systemPrompt())
                                                .user(renderBasicChatPrompt(ctx)),
                                        ctx.assembled().sessionKey(),
                                        ctx.assembled().userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            String answer = result.value();
            if (!StringUtils.hasText(answer)) {
                LocalSkillFallbackService.LocalSkillResult localResult = oparLoopEngine.tryLocalFallbackResult(ctx.assembled().question());
                if (localResult != null) {
                    answer = oparLoopEngine.narrateLocalExecution(ctx.systemPrompt(), ctx.assembled(), localResult);
                } else {
                    answer = fallbackResponder.respond(modelTransportGuardService.disabledModelReason(ctx.activeClient()), ctx.assembled());
                }
            }
            return new ChatExecutionResult(
                    ctx.assembled().observePrompt(),
                    "BASIC_STREAM: 普通聊天最短路径。",
                    "未挂载工具，直接模型回答。",
                    answer,
                    true
            );
        } catch (Exception ex) {
            log.warn("BasicStream 执行失败，sessionKey={}, reason={}", ctx.assembled().sessionKey(), ex.getMessage());
            modelTransportGuardService.markFailure(ctx.activeClient().providerId(), ex);
            LocalSkillFallbackService.LocalSkillResult localResult = oparLoopEngine.tryLocalFallbackResult(ctx.assembled().question());
            if (localResult != null) {
                String answer = oparLoopEngine.narrateLocalExecution(ctx.systemPrompt(), ctx.assembled(), localResult);
                return new ChatExecutionResult(ctx.assembled().observePrompt(), "BASIC_STREAM: 降级到本地技能。", localResult.executionDetails(), answer, false);
            }
            return new ChatExecutionResult(
                    ctx.assembled().observePrompt(),
                    "BASIC_STREAM: 模型调用失败。",
                    "模型不可用: " + ex.getMessage(),
                    fallbackResponder.respond(modelTransportGuardService.disabledModelReason(ctx.activeClient()), ctx.assembled()),
                    false
            );
        }
    }

    private boolean handlePartialAnswer(ChatContext context,
                                         StringBuilder fullAnswer,
                                         Throwable streamFailure,
                                         SseEmitter emitter,
                                         String lockToken,
                                         AtomicBoolean lockReleased) {
        String partial = fullAnswer == null ? "" : fullAnswer.toString().trim();
        if (!StringUtils.hasText(partial)) {
            return false;
        }
        String reason = chatResponsePolicyService.simplifyFailureReason(
                streamFailure == null ? "" : streamFailure.getMessage());
        String notice = "\n\n生成中断：" + reason + " 已保留上面已经收到的内容；如果要完整回答，请点\u201C重试上一条\u201D。";
        String answer = partial + notice;
        sseEventBridge.sendStatus(emitter, "模型流式连接中断，已保留部分内容");
        sseEventBridge.sendToken(emitter, notice);
        ChatExecutionResult partialResult = new ChatExecutionResult(
                context.assembled().observePrompt(),
                "STREAM_BASIC_INTERRUPTED: 模型流式输出中途断开。",
                "已保留部分模型输出。reason=" + TextUtils.safe(
                        streamFailure == null ? "" : streamFailure.getMessage()),
                answer,
                false
        );
        chatResultPersister.persist(context, answer, partialResult, ChatPersistenceIntent.TERMINAL_RESULT);
        reportResult(context, partialResult, answer);
        releaseLock(emitter, context, lockToken, lockReleased);
        return true;
    }

    private void reportResult(
            ChatContext context,
            ChatExecutionResult result,
            String answer
    ) {
        if (lifecycleObserver == null) {
            return;
        }
        try {
            lifecycleObserver.resultReturned(context, result, answer, Instant.now());
        } catch (RuntimeException ex) {
            log.error(
                    "canonical lifecycle projection failed after basic stream persistence, requestId={}",
                    context.requestId(),
                    ex
            );
        }
    }

    private void releaseLock(SseEmitter emitter,
                              ChatContext context,
                              String lockToken,
                              AtomicBoolean lockReleased) {
        if (lockReleased.compareAndSet(false, true)) {
            chatGuardService.releaseSessionLock(context.session().getSessionKey(), lockToken);
        }
        sseEventBridge.completeEmitter(emitter);
    }

    String renderBasicChatPrompt(ChatContext ctx) {
        String injection = TypedContextPromptRenderer.promptPrefix(ctx);
        String question = TypedContextPromptRenderer.question(ctx);
        return """
                %s## 用户当前问题
                %s

                直接回答用户问题，保持中文自然、完整、有重点。
                这是普通聊天路径：不要调用工具，不要输出内部阶段名，不要提到路由、OPAR、兜底或实现细节。
                如果问题本身需要外部文件、网页、项目源码或实时数据，简洁说明需要进入 Agent 工具链路。
                """.formatted(injection, question);
    }

    private boolean isGeneralIntent(ChatContext ctx) {
        String intent = normalizeIntent(ctx.intent());
        if ("control_plane".equals(intent)
                || "model_control".equals(intent)
                || "local_files".equals(intent)) {
            return false;
        }
        AgentDecision decision = ctx.decision();
        if (decision != null && decision.isGeneral()) return true;
        return "general".equals(intent);
    }

    private String normalizeIntent(String rawIntent) {
        if (!StringUtils.hasText(rawIntent)) return "general";
        return rawIntent.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean useSimplifiedMode(String executionMode) {
        if (!StringUtils.hasText(executionMode)) return true;
        return !"opar".equals(executionMode.trim().toLowerCase(Locale.ROOT));
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
}
