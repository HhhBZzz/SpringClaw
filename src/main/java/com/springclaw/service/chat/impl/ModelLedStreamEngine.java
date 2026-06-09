package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型主导流式引擎：模型自行调用工具（ToolContext 生命周期在此管理）。
 * 从 ChatServiceImpl.streamModelLedAnswer 提取。
 */
@Service
public class ModelLedStreamEngine {

    private static final Logger log = LoggerFactory.getLogger(ModelLedStreamEngine.class);

    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final ToolOrchestrator toolOrchestrator;
    private final SseEventBridge sseEventBridge;
    private final ChatResultPersister chatResultPersister;
    private final ChatGuardService chatGuardService;

    /**
     * 流式失败回调：当模型流式连接完全失败（无部分内容）时，
     * 由 ChatServiceImpl 提供降级逻辑（streamBlockingFallback）。
     */
    @FunctionalInterface
    public interface OnStreamFailure {
        void handle(ChatContext ctx, Throwable error, SseEmitter emitter,
                    String lockToken, AtomicBoolean lockReleased);
    }

    public ModelLedStreamEngine(ConversationAdvisorSupport conversationAdvisorSupport,
                                ModelTransportGuardService modelTransportGuardService,
                                ChatResponsePolicyService chatResponsePolicyService,
                                LlmUsageRecordService llmUsageRecordService,
                                ToolOrchestrator toolOrchestrator,
                                SseEventBridge sseEventBridge,
                                ChatResultPersister chatResultPersister,
                                ChatGuardService chatGuardService) {
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.modelTransportGuardService = modelTransportGuardService;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.toolOrchestrator = toolOrchestrator;
        this.sseEventBridge = sseEventBridge;
        this.chatResultPersister = chatResultPersister;
        this.chatGuardService = chatGuardService;
    }

    /**
     * SSE 流式执行：模型主导，可调用工具。
     * 成功时自行持久化并完成 SSE；部分内容中断时保留部分内容；
     * 完全失败时委托给 fallbackHandler 进行降级处理。
     */
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             OnStreamFailure fallbackHandler,
                             StreamInterruptHandler interruptHandler) {
        long startedAt = System.currentTimeMillis();
        sseEventBridge.sendTrace(emitter, context, "选择能力", "agent", "success",
                "模型主导流式链路，可按需调用工具。", 0L);
        sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "started",
                context.activeClient().displayName(), 0L);
        sseEventBridge.sendStatus(emitter, "正在调用模型并实时输出");

        StringBuilder fullAnswer = new StringBuilder();
        AiProviderService.ActiveChatClient streamClient = context.activeClient();
        final ChatResponse[] latestStreamResponse = new ChatResponse[1];
        AtomicReference<ToolExecutionContextHolder.Scope> toolScopeRef = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);

        try {
            Object[] tools = toolOrchestrator == null
                    ? new Object[0]
                    : toolOrchestrator.selectAgentTools(context.channel(), context.userId(), context.decision());
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
                                sseEventBridge.sendToken(emitter, token);
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
                            fallbackHandler.handle(context,
                                    new IllegalStateException("模型未返回可见内容"),
                                    emitter, lockToken, lockReleased);
                            return;
                        }
                        chatResultPersister.persist(context, answer, new ChatExecutionResult(
                                context.assembled().observePrompt(),
                                "STREAM_AGENT: 模型直接流式生成，并可自行调用工具。",
                                "使用 responseMode=agent 的 model-led streaming 分支。",
                                answer,
                                true
                        ));
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
                        closeToolScope(toolScopeRef);
                        modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                        sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "failed",
                                chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                                System.currentTimeMillis() - startedAt);
                        if (interruptHandler.handlePartial(context, fullAnswer, ex, emitter, lockToken, lockReleased)) {
                            return;
                        }
                        sseEventBridge.sendTrace(emitter, context, "降级处理", "fallback", "started",
                                "模型主导流式链路失败，转入 Agent 兜底链路。", 0L);
                        fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
                    })
                    .doFinally(signalType -> closeToolScope(toolScopeRef))
                    .subscribe();
            disposableRef.set(disposable);
            return disposable;
        } catch (Exception ex) {
            if (finished.compareAndSet(false, true)) {
                closeToolScope(toolScopeRef);
                modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                sseEventBridge.sendTrace(emitter, context, "调用模型", "model", "failed",
                        chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                        System.currentTimeMillis() - startedAt);
                if (interruptHandler.handlePartial(context, fullAnswer, ex, emitter, lockToken, lockReleased)) {
                    return null;
                }
                sseEventBridge.sendTrace(emitter, context, "降级处理", "fallback", "started",
                        "模型主导流式链路启动失败，转入 Agent 兜底链路。", 0L);
                fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
            }
            return null;
        }
    }

    /**
     * 部分内容处理回调：当模型流式输出中途断开时，尝试保留已有部分内容。
     */
    @FunctionalInterface
    public interface StreamInterruptHandler {
        boolean handlePartial(ChatContext context, StringBuilder fullAnswer, Throwable streamFailure,
                              SseEmitter emitter, String lockToken, AtomicBoolean lockReleased);
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

    private void releaseLock(SseEmitter emitter,
                              ChatContext context,
                              String lockToken,
                              AtomicBoolean lockReleased) {
        if (lockReleased.compareAndSet(false, true)) {
            chatGuardService.releaseSessionLock(context.session().getSessionKey(), lockToken);
        }
        sseEventBridge.completeEmitter(emitter);
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
                """.formatted(TextUtils.safe(assembled == null ? null : assembled.question()));
    }
}
