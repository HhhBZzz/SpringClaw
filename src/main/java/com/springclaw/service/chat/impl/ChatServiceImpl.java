package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentActionProposal;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRun;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.proposal.PendingToolApprovalException;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话服务编排层。
 * SSE 事件的序列化与推送委托给 {@link SseEventBridge}。
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
    private final AgentActionProposalService actionProposalService;
    private final AgentRuntimeEngine agentRuntimeEngine;
    private final EngineSelector engineSelector;
    private final LocalExecutionSupport localExecutionSupport;
    private final SseEventBridge sseEventBridge;
    private final ToolInvocationProposalService proposalService;
    private final RunLifecycleObserver lifecycleObserver;
    private final RunIdentityFactory runIdentityFactory;
    private final boolean modelLedStreamingEnabled;
    private final boolean basicStreamingEnabled;

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
                           AgentActionProposalService actionProposalService,
                           AgentRuntimeEngine agentRuntimeEngine,
                           EngineSelector engineSelector,
                           LocalExecutionSupport localExecutionSupport,
                           SseEventBridge sseEventBridge,
                           ToolInvocationProposalService proposalService,
                           RunLifecycleObserver lifecycleObserver,
                           RunIdentityFactory runIdentityFactory,
                           @Value("${springclaw.chat.model-led-streaming-enabled:false}") boolean modelLedStreamingEnabled,
                           @Value("${springclaw.chat.basic-streaming-enabled:true}") boolean basicStreamingEnabled) {
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
        this.actionProposalService = actionProposalService;
        this.agentRuntimeEngine = agentRuntimeEngine;
        this.engineSelector = engineSelector;
        this.localExecutionSupport = localExecutionSupport;
        this.sseEventBridge = sseEventBridge;
        this.proposalService = proposalService;
        this.lifecycleObserver = lifecycleObserver;
        this.runIdentityFactory = runIdentityFactory;
        this.modelLedStreamingEnabled = modelLedStreamingEnabled;
        this.basicStreamingEnabled = basicStreamingEnabled;
    }

    /**
     * 测试兼容构造函数：不涉及 SSE 的场景（chat / executeTaskMessage）。
     */
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
                    MetaGuardExecutor metaGuardExecutor) {
        this(aiProviderService, chatGuardService, oparLoopEngine, simplifiedOparEngine,
                chatResponsePolicyService, modelTransportGuardService, llmUsageRecordService,
                conversationAdvisorSupport, chatContextFactory, chatResultPersister,
                metaGuardExecutor, null, null, null, null, null, null, null, null,
                new DefaultRunIdentityFactory(), false, true);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return chat(new AcceptedChatCommand(runIdentityFactory.create(), request));
    }

    @Override
    public ChatResponse chat(AcceptedChatCommand command) {
        String acceptedRunId = runIdentityFactory.accept(command.runId());
        TaskChatExecutionResult result = executeInternal(
                command.request(),
                true,
                true,
                acceptedRunId
        );
        return new ChatResponse(
                acceptedRunId,
                result.sessionKey(),
                result.answer(),
                aiProviderService.activeClient().displayName(),
                System.currentTimeMillis()
        );
    }

    @Override
    public SseEmitter stream(ChatRequest request) {
        return stream(new AcceptedChatCommand(runIdentityFactory.create(), request));
    }

    @Override
    public SseEmitter stream(AcceptedChatCommand command) {
        String acceptedRunId = runIdentityFactory.accept(command.runId());
        ChatRequest request = command.request();
        chatGuardService.checkRateLimit(request.sessionKey());
        String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
        AtomicBoolean lockReleased = new AtomicBoolean(false);
        SseEmitter emitter = new SseEmitter(1_800_000L); // 30 分钟超时，容纳自主循环等长时间执行
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
        CompletableFuture.runAsync(() -> executeStream(
                request,
                acceptedRunId,
                lockToken,
                lockReleased,
                emitter,
                disposableRef
        ));
        return emitter;
    }

    private void executeStream(ChatRequest request,
                               String acceptedRunId,
                               String lockToken,
                               AtomicBoolean lockReleased,
                               SseEmitter emitter,
                               AtomicReference<Disposable> disposableRef) {
        try {
            long requestStartedAt = System.currentTimeMillis();
            sseEventBridge.sendTrace(emitter, acceptedRunId, "接收请求", "request", "started", "已收到用户输入，准备组装上下文。", 0L);
            sseEventBridge.sendStatus(emitter, "正在组织上下文");
            ChatContext context = chatContextFactory.build(
                    request,
                    true,
                    acceptedRunId
            );
            if (lifecycleObserver != null) {
                lifecycleObserver.contextAndDecisionObserved(context, Instant.now());
            }
            sseEventBridge.sendMeta(emitter, context);
            sseEventBridge.sendDecision(emitter, context);
            String decisionPath = context.decision() == null ? "" : context.decision().executionPath();
            String decisionReason = context.decision() == null ? context.routingReason() : context.decision().reason();
            sseEventBridge.sendTrace(emitter, context, "判断意图", "route", "success",
                    "intent=" + TextUtils.safe(context.intent()) + "，path=" + TextUtils.safe(decisionPath) + "，原因=" + TextUtils.safe(decisionReason),
                    System.currentTimeMillis() - requestStartedAt);
            // 统一路由：通过 EngineSelector 选择引擎
            AgentEngine engine = engineSelector.select(context);
            if (lifecycleObserver != null) {
                lifecycleObserver.executionStarted(context, engine.name(), Instant.now());
            }
            if (shouldRequestActionConfirmation(context)) {
                streamActionRequired(context, lockToken, lockReleased, emitter);
                return;
            }
            if (engine instanceof AgentEngine.StreamableAgentEngine streamable) {
                streamable.stream(context, emitter, lockToken, lockReleased, disposableRef,
                        (ctx, error, em, lt, lr) -> streamBlockingFallback(ctx, error, lt, lr, em));
                return;
            }
            if (engine instanceof AgentRuntimeEngine runtimeEngine) {
                streamAgentRuntimeAnswer(context, lockToken, lockReleased, emitter);
                return;
            }
            // 非 Streamable 引擎：execute + reflect
            sseEventBridge.sendTrace(emitter, context, "选择能力", "agent", "started", "进入 Agent 执行链路。", 0L);
            sseEventBridge.sendStatus(emitter, "正在执行 Agent");
            ChatExecutionResult executionResult = runAgentExecution(context);
            emitLocalExecutionTraceIfNeeded(emitter, context, executionResult);
            sseEventBridge.sendTrace(emitter, context, "执行能力", "agent", "success", summarizeExecution(executionResult), 0L);

            if (shouldSendImmediateAnswer(context, executionResult)) {
                streamImmediateAnswer(context, executionResult, emitter);
                releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                sseEventBridge.sendTrace(emitter, context, "完成", "final", "success", "已写入会话结果。", 0L);
                sseEventBridge.completeEmitter(emitter);
                return;
            }

            sseEventBridge.sendTrace(emitter, context, "模型整理", "model", "started", "正在基于执行结果生成最终回答。", 0L);
            streamReflectAnswer(context, executionResult, lockToken, lockReleased, emitter, disposableRef);
        } catch (PendingToolApprovalException pending) {
            handlePendingApproval(emitter, request, lockToken, lockReleased, pending);
        } catch (Exception ex) {
            log.warn("流式聊天启动失败，sessionKey={}, reason={}", request.sessionKey(), ex.getMessage());
            if (lifecycleObserver != null) {
                lifecycleObserver.failed(
                        acceptedRunId, "LEGACY_STREAM_FAILED", ex, Instant.now()
                );
            }
            sseEventBridge.sendError(emitter, chatResponsePolicyService.simplifyFailureReason(ex.getMessage()));
            releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        }
    }

    /**
     * 包可见用于精确单测 PendingToolApprovalException 分支。
     * agent_run status update is deferred to a follow-up — confirmation visibility is via SSE + REST polling.
     */
    void handlePendingApproval(SseEmitter emitter,
                               ChatRequest request,
                               String lockToken,
                               AtomicBoolean lockReleased,
                               PendingToolApprovalException pending) {
        log.info("Tool proposal pending approval, sessionKey={}, proposalId={}",
                request.sessionKey(), pending.proposalId());
        proposalService.findByProposalId(pending.proposalId())
                .ifPresent(proposal -> sseEventBridge.sendToolActionRequired(emitter, proposal));
        releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
        sseEventBridge.completeEmitter(emitter);
    }

    private boolean shouldRequestActionConfirmation(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        return decision != null && decision.requiresConfirmation();
    }

    private void emitLocalExecutionTraceIfNeeded(SseEmitter emitter,
                                                 ChatContext context,
                                                 ChatExecutionResult executionResult) {
        if (!isLocalExecutionResult(executionResult)) {
            return;
        }
        sseEventBridge.sendTrace(emitter, context, "本地短路执行", "local_fallback", "success",
                summarizeLocalExecution(executionResult), 0L);
    }

    private boolean isLocalExecutionResult(ChatExecutionResult executionResult) {
        if (executionResult == null || executionResult.modelEnabled()) {
            return false;
        }
        return TextUtils.safe(executionResult.plan()).contains("执行路线:")
                || TextUtils.safe(executionResult.action()).contains("真实执行结果:");
    }

    private String summarizeLocalExecution(ChatExecutionResult executionResult) {
        String route = extractMarkerLine(executionResult.plan(), "执行路线:");
        String result = extractMarkerBlock(executionResult.action(), "真实执行结果:");
        StringBuilder detail = new StringBuilder("OPAR 入口命中本地短路，跳过模型 Plan/Act。执行路线");
        if (StringUtils.hasText(route)) {
            detail.append(": ").append(route);
        }
        if (StringUtils.hasText(result)) {
            detail.append("；结果: ").append(TextUtils.truncate(result, 180));
        }
        return detail.toString();
    }

    private String extractMarkerLine(String text, String marker) {
        String value = TextUtils.safe(text);
        int index = value.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String afterMarker = value.substring(index + marker.length()).trim();
        int newline = afterMarker.indexOf('\n');
        return (newline >= 0 ? afterMarker.substring(0, newline) : afterMarker).trim();
    }

    private String extractMarkerBlock(String text, String marker) {
        String value = TextUtils.safe(text);
        int index = value.indexOf(marker);
        if (index < 0) {
            return "";
        }
        return value.substring(index + marker.length()).trim();
    }

    private void streamActionRequired(ChatContext context,
                                      String lockToken,
                                      AtomicBoolean lockReleased,
                                      SseEmitter emitter) {
        try {
            sseEventBridge.sendTrace(emitter, context, "等待确认", "agent", "started",
                    "该动作风险等级=" + context.decision().riskLevel() + "，需要用户确认。", 0L);
            AgentActionProposal proposal = actionProposalService == null
                    ? null
                    : actionProposalService.createProposal(
                    context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.roleCode(),
                    context.requestId(),
                    context.effectiveUserMessage(),
                    context.decision()
            );
            if (proposal != null) {
                sseEventBridge.sendActionRequired(emitter, proposal);
                if (lifecycleObserver != null) {
                    lifecycleObserver.confirmationRequired(
                            context.requestId(), proposal.proposalId(), Instant.now()
                    );
                }
            }
            String answer = proposal == null
                    ? "这个动作需要确认，但当前确认服务不可用。"
                    : "我已经判断这不是普通问答，而是需要确认的动作。请在确认卡片里确认或取消；确认前不会产生副作用。";
            sseEventBridge.sendAnswerChunks(emitter, answer);
            chatResultPersister.persist(context, answer, new ChatExecutionResult(
                    context.assembled().observePrompt(),
                    "ACTION_REQUIRED: Agent 自动决策要求用户确认。",
                    context.decision().reason(),
                    answer,
                    false
            ), ChatPersistenceIntent.CONFIRMATION_SUSPENSION);
            sseEventBridge.sendTrace(emitter, context, "等待确认", "agent", "success",
                    proposal == null ? "确认服务不可用。" : "已生成 action proposal: " + proposal.proposalId(), 0L);
        } catch (Exception ex) {
            log.warn("生成动作确认卡失败，sessionKey={}, reason={}", context.session().getSessionKey(), ex.getMessage());
            sseEventBridge.sendError(emitter, chatResponsePolicyService.simplifyFailureReason(ex.getMessage()));
        } finally {
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        }
    }

    private void streamImmediateAnswer(ChatContext context,
                                       ChatExecutionResult executionResult,
                                       SseEmitter emitter) {
        String answer = resolveFinalAnswer(context, executionResult);
        sseEventBridge.sendAnswerChunks(emitter, answer);
        chatResultPersister.persist(context, answer, executionResult, ChatPersistenceIntent.TERMINAL_RESULT);
        if (lifecycleObserver != null) {
            lifecycleObserver.resultReturned(context, executionResult, answer, Instant.now());
        }
    }

    private void streamAgentRuntimeAnswer(ChatContext context,
                                          String lockToken,
                                          AtomicBoolean lockReleased,
                                          SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        try {
            sseEventBridge.sendTrace(emitter, context, "选择能力", "agent", "started",
                    "进入统一 AgentRuntimeEngine。", 0L);
            sseEventBridge.sendStatus(emitter, "正在执行 Agent Runtime");
            AgentRun run = agentRuntimeEngine.run(context);
            sseEventBridge.emitAndRecordRun(emitter, context, run);
            ChatExecutionResult executionResult = run.executionResult();
            String answer = resolveFinalAnswer(context, executionResult);
            sseEventBridge.sendAnswerChunks(emitter, answer);
            chatResultPersister.persist(context, answer, executionResult, ChatPersistenceIntent.TERMINAL_RESULT);
            if (lifecycleObserver != null) {
                lifecycleObserver.resultReturned(context, executionResult, answer, Instant.now());
            }
            boolean sufficient = run.verification() == null || run.verification().sufficient();
            sseEventBridge.sendTrace(emitter, context, "完成", "final", sufficient ? "success" : "failed",
                    sufficient ? "Agent Runtime 已完成。" : "Agent Runtime 已返回证据不足结果。", System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.warn("AgentRuntimeEngine 执行失败，sessionKey={}, reason={}", context.session().getSessionKey(), ex.getMessage());
            sseEventBridge.sendTrace(emitter, context, "执行 Agent Runtime", "agent", "failed",
                    chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), System.currentTimeMillis() - startedAt);
            streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
            return;
        }
        releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
        sseEventBridge.completeEmitter(emitter);
    }

    private void streamBlockingFallback(ChatContext context,
                                        Throwable streamFailure,
                                        String lockToken,
                                        AtomicBoolean lockReleased,
                                        SseEmitter emitter) {
        try {
            sseEventBridge.sendStatus(emitter, "流式通道异常，正在降级整理结果");
            ChatExecutionResult fallbackResult = runAgentExecution(context);
            String answer = resolveFinalAnswer(context, fallbackResult);
            if (!StringUtils.hasText(answer)) {
                answer = metaGuardExecutor.fallbackAnswer(
                        streamFailure == null ? "模型未返回内容" : streamFailure.getMessage(),
                        context.assembled()
                );
            }
            sseEventBridge.sendAnswerChunks(emitter, answer);
            chatResultPersister.persist(context, answer, fallbackResult, ChatPersistenceIntent.TERMINAL_RESULT);
            if (lifecycleObserver != null) {
                lifecycleObserver.resultReturned(context, fallbackResult, answer, Instant.now());
            }
            sseEventBridge.sendTrace(emitter, context, "降级处理", "fallback", "success",
                    "已通过现有 Agent 链路整理结果。", 0L);
        } catch (Exception fallbackEx) {
            log.warn("流式降级回答失败，sessionKey={}, reason={}", context.session().getSessionKey(), fallbackEx.getMessage());
            if (lifecycleObserver != null) {
                lifecycleObserver.failed(
                        context.requestId(), "LEGACY_STREAM_FAILED", fallbackEx, Instant.now()
                );
            }
            sseEventBridge.sendTrace(emitter, context, "降级处理", "fallback", "failed",
                    chatResponsePolicyService.simplifyFailureReason(fallbackEx.getMessage()), 0L);
            sseEventBridge.sendError(emitter, chatResponsePolicyService.simplifyFailureReason(fallbackEx.getMessage()));
        } finally {
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            sseEventBridge.sendTrace(emitter, context, "完成", "final", "success", "流式请求已结束。", 0L);
            sseEventBridge.completeEmitter(emitter);
        }
    }

    private String summarizeExecution(ChatExecutionResult executionResult) {
        if (executionResult == null) {
            return "执行完成，但没有返回执行摘要。";
        }
        String action = TextUtils.safe(executionResult.action());
        if (StringUtils.hasText(action)) {
            return action.length() > 180 ? action.substring(0, 180) + "..." : action;
        }
        return StringUtils.hasText(executionResult.reflect()) ? "已生成最终回答。" : "执行完成，等待模型整理最终回答。";
    }

    private void streamReflectAnswer(ChatContext context,
                                     ChatExecutionResult executionResult,
                                     String lockToken,
                                     AtomicBoolean lockReleased,
                                     SseEmitter emitter,
                                     AtomicReference<Disposable> disposableRef) {
        StringBuilder fullAnswer = new StringBuilder();
        AiProviderService.ActiveChatClient reflectClient = aiProviderService.activeClient();
        String reflectPrompt = oparLoopEngine.renderReflectPrompt(context, executionResult.plan(), executionResult.action());
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
                            sseEventBridge.sendToken(emitter, token);
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
                                sseEventBridge.sendToken(emitter, "\n\n【自动修正回答】\n" + repaired);
                            }
                        } catch (Exception ex) {
                            log.warn("流式回答自动修正失败，sessionKey={}, reason={}", context.assembled().sessionKey(), ex.getMessage());
                        }
                    }
                    chatResultPersister.persist(context, fullAnswer.toString(), executionResult, ChatPersistenceIntent.TERMINAL_RESULT);
                    if (lifecycleObserver != null) {
                        lifecycleObserver.resultReturned(
                                context, executionResult, fullAnswer.toString(), Instant.now()
                        );
                    }
                    sseEventBridge.sendTrace(emitter, context, "模型整理", "model", "success",
                            reflectClient.displayName(), 0L);
                    sseEventBridge.sendTrace(emitter, context, "完成", "final", "success", "已生成最终回答。", 0L);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    sseEventBridge.completeEmitter(emitter);
                })
                .doOnError(ex -> {
                    modelTransportGuardService.markFailure(reflectClient.providerId(), ex);
                    sseEventBridge.sendTrace(emitter, context, "模型整理", "model", "failed",
                            chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), 0L);
                    String fallback = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
                    if (!StringUtils.hasText(fallback)) {
                        fallback = metaGuardExecutor.fallbackAnswer(modelTransportGuardService.disabledModelReason(reflectClient), context.assembled());
                    }
                    fullAnswer.setLength(0);
                    fullAnswer.append(fallback);
                    sseEventBridge.sendAnswerChunks(emitter, fallback);
                    chatResultPersister.persist(context, fullAnswer.toString(), executionResult, ChatPersistenceIntent.TERMINAL_RESULT);
                    if (lifecycleObserver != null) {
                        lifecycleObserver.resultReturned(
                                context, executionResult, fullAnswer.toString(), Instant.now()
                        );
                    }
                    sseEventBridge.sendTrace(emitter, context, "完成", "final", "failed", "已返回降级结果。", 0L);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    sseEventBridge.completeEmitter(emitter);
                })
                .subscribe();
        disposableRef.set(disposable);
    }

    public TaskChatExecutionResult executeTaskMessage(ChatRequest request, boolean persistResult) {
        return executeTaskMessage(
                request,
                persistResult,
                runIdentityFactory.create()
        );
    }

    public TaskChatExecutionResult executeTaskMessage(
            ChatRequest request,
            boolean persistResult,
            String runId
    ) {
        return executeInternal(
                request,
                false,
                persistResult,
                runIdentityFactory.accept(runId)
        );
    }

    private TaskChatExecutionResult executeInternal(ChatRequest request,
                                                    boolean enforceRateLimit,
                                                    boolean persistResult,
                                                    String acceptedRunId) {
        if (enforceRateLimit) {
            chatGuardService.checkRateLimit(request.sessionKey());
        }
        String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
        try {
            ChatContext context = chatContextFactory.build(
                    request,
                    persistResult,
                    acceptedRunId
            );
            Instant contextAt = Instant.now();
            if (lifecycleObserver != null) {
                lifecycleObserver.contextAndDecisionObserved(context, contextAt);
            }
            AgentEngine engine = engineSelector.select(context);
            if (lifecycleObserver != null) {
                lifecycleObserver.executionStarted(context, engine.name(), Instant.now());
            }
            ChatExecutionResult executionResult;
            try {
                executionResult = executeSelected(context, engine);
            } catch (RuntimeException ex) {
                if (lifecycleObserver != null) {
                    lifecycleObserver.failed(
                            context.requestId(),
                            "LEGACY_EXECUTION_FAILED",
                            ex,
                            Instant.now()
                    );
                }
                throw ex;
            }
            String finalAnswer = resolveFinalAnswer(context, executionResult);
            if (persistResult) {
                chatResultPersister.persist(context, finalAnswer, executionResult, ChatPersistenceIntent.TERMINAL_RESULT);
            }
            if (lifecycleObserver != null) {
                lifecycleObserver.resultReturned(
                        context, executionResult, finalAnswer, Instant.now()
                );
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
        return executeSelected(context, engineSelector.select(context));
    }

    private ChatExecutionResult executeSelected(ChatContext context, AgentEngine engine) {
        if (engine instanceof AgentRuntimeEngine runtimeEngine) {
            AgentRun run = runtimeEngine.run(context);
            if (sseEventBridge != null) {
                sseEventBridge.recordRunTrace(context, run);
            }
            return run.executionResult();
        }
        return engine.execute(context, metaGuardExecutor::fallbackAnswer);
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
            LocalSkillFallbackService.LocalSkillResult localResult = localExecutionSupport != null
                    ? localExecutionSupport.tryFallback(context.assembled().question(), true)
                    : null;
            if (localResult != null) {
                return localExecutionSupport.narrate(context.systemPrompt(), context.assembled(), localResult);
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
