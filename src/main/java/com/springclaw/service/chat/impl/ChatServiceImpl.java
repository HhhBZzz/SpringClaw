package com.springclaw.service.chat.impl;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentActionProposal;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentRun;
import com.springclaw.service.agent.AgentRunTraceEvent;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.agent.VerificationResult;
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
    private final AgentActionProposalService actionProposalService;
    private final AgentRunTraceService agentRunTraceService;
    private final AgentRuntimeEngine agentRuntimeEngine;
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
                           AgentRunTraceService agentRunTraceService,
                           AgentRuntimeEngine agentRuntimeEngine,
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
        this.agentRunTraceService = agentRunTraceService;
        this.agentRuntimeEngine = agentRuntimeEngine;
        this.modelLedStreamingEnabled = modelLedStreamingEnabled;
        this.basicStreamingEnabled = basicStreamingEnabled;
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
                    ToolOrchestrator toolOrchestrator,
                    AgentActionProposalService actionProposalService,
                    AgentRunTraceService agentRunTraceService,
                    boolean modelLedStreamingEnabled,
                    boolean basicStreamingEnabled) {
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
                actionProposalService,
                agentRunTraceService,
                null,
                modelLedStreamingEnabled,
                basicStreamingEnabled);
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
                null,
                null,
                null,
                false,
                true);
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
                null,
                null,
                null,
                false,
                true);
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
            long requestStartedAt = System.currentTimeMillis();
            sendTrace(emitter, "", "接收请求", "request", "started", "已收到用户输入，准备组装上下文。", 0L);
            sendStatus(emitter, "正在组织上下文");
            ChatContext context = chatContextFactory.build(request, true);
            sendMeta(emitter, context);
            sendDecision(emitter, context);
            sendTrace(emitter,
                    context,
                    "判断意图",
                    "route",
                    "success",
                    "intent=" + safe(context.intent()) + "，path=" + safe(context.decision() == null ? "" : context.decision().executionPath()) + "，原因=" + safe(context.decision() == null ? context.routingReason() : context.decision().reason()),
                    System.currentTimeMillis() - requestStartedAt);
            if (shouldRequestActionConfirmation(context)) {
                streamActionRequired(context, lockToken, lockReleased, emitter);
                return;
            }
            if (shouldUseBasicModelStreaming(context)) {
                streamBasicModelAnswer(context, lockToken, lockReleased, emitter, disposableRef);
                return;
            }
            if (shouldUseModelLedStreaming(context)) {
                streamModelLedAnswer(context, lockToken, lockReleased, emitter, disposableRef);
                return;
            }
            if (shouldUseAgentRuntime(context)) {
                streamAgentRuntimeAnswer(context, lockToken, lockReleased, emitter);
                return;
            }
            sendTrace(emitter, context, "选择能力", "agent", "started", "进入 Agent 执行链路。", 0L);
            sendStatus(emitter, "正在执行 Agent");
            ChatExecutionResult executionResult = runAgentExecution(context);
            sendTrace(emitter, context, "执行能力", "agent", "success", summarizeExecution(executionResult), 0L);

            if (shouldSendImmediateAnswer(context, executionResult)) {
                streamImmediateAnswer(context, executionResult, emitter);
                releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                sendTrace(emitter, context, "完成", "final", "success", "已写入会话结果。", 0L);
                completeEmitter(emitter);
                return;
            }

            sendTrace(emitter, context, "模型整理", "model", "started", "正在基于执行结果生成最终回答。", 0L);
            streamReflectAnswer(context, executionResult, lockToken, lockReleased, emitter, disposableRef);
        } catch (Exception ex) {
            log.warn("流式聊天启动失败，sessionKey={}, reason={}", request.sessionKey(), ex.getMessage());
            sendError(emitter, ex.getMessage());
            releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
            completeEmitter(emitter);
        }
    }

    private boolean shouldRequestActionConfirmation(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        return decision != null && decision.requiresConfirmation();
    }

    private void streamActionRequired(ChatContext context,
                                      String lockToken,
                                      AtomicBoolean lockReleased,
                                      SseEmitter emitter) {
        try {
            sendTrace(emitter, context, "等待确认", "agent", "started",
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
                sendActionRequired(emitter, proposal);
            }
            String answer = proposal == null
                    ? "这个动作需要确认，但当前确认服务不可用。"
                    : "我已经判断这不是普通问答，而是需要确认的动作。请在确认卡片里确认或取消；确认前不会产生副作用。";
            sendAnswerChunks(emitter, answer);
            chatResultPersister.persist(context, answer, new ChatExecutionResult(
                    context.assembled().observePrompt(),
                    "ACTION_REQUIRED: Agent 自动决策要求用户确认。",
                    context.decision().reason(),
                    answer,
                    false
            ));
            sendTrace(emitter, context, "等待确认", "agent", "success",
                    proposal == null ? "确认服务不可用。" : "已生成 action proposal: " + proposal.proposalId(), 0L);
        } catch (Exception ex) {
            log.warn("生成动作确认卡失败，sessionKey={}, reason={}", context.session().getSessionKey(), ex.getMessage());
            sendError(emitter, ex.getMessage());
        } finally {
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            completeEmitter(emitter);
        }
    }

    private void streamBasicModelAnswer(ChatContext context,
                                        String lockToken,
                                        AtomicBoolean lockReleased,
                                        SseEmitter emitter,
                                        AtomicReference<Disposable> disposableRef) {
        long startedAt = System.currentTimeMillis();
        sendTrace(emitter, context, "选择能力", "model", "success",
                "普通聊天命中最短路径：不挂载工具，不进入多步规划。", 0L);
        sendTrace(emitter, context, "调用模型", "model", "started",
                context.activeClient().displayName(), 0L);
        sendStatus(emitter, "正在调用模型并实时输出");
        StringBuilder fullAnswer = new StringBuilder();
        AiProviderService.ActiveChatClient streamClient = context.activeClient();
        final org.springframework.ai.chat.model.ChatResponse[] latestStreamResponse = new org.springframework.ai.chat.model.ChatResponse[1];
        AtomicBoolean finished = new AtomicBoolean(false);

        try {
            Disposable disposable = conversationAdvisorSupport.apply(
                            streamClient.chatClient().prompt()
                                    .system(context.systemPrompt())
                                    .user(renderBasicChatPrompt(context.assembled())),
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
                            streamBlockingFallback(context, new IllegalStateException("模型未返回可见内容"),
                                    lockToken, lockReleased, emitter);
                            return;
                        }
                        chatResultPersister.persist(context, answer, new ChatExecutionResult(
                                context.assembled().observePrompt(),
                                "BASIC_STREAM: 普通聊天走最短模型流式链路。",
                                "未挂载工具，未进入多步规划。",
                                answer,
                                true
                        ));
                        sendTrace(emitter, context, "调用模型", "model", "success",
                                streamClient.displayName(), System.currentTimeMillis() - startedAt);
                        sendTrace(emitter, context, "完成", "final", "success",
                                "已生成最终回答。", System.currentTimeMillis() - startedAt);
                        releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                        completeEmitter(emitter);
                    })
                    .doOnError(ex -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                        sendTrace(emitter, context, "调用模型", "model", "failed",
                                chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), System.currentTimeMillis() - startedAt);
                        if (streamInterruptedPartialAnswer(context, fullAnswer, ex, lockToken, lockReleased, emitter)) {
                            return;
                        }
                        sendTrace(emitter, context, "降级处理", "fallback", "started",
                                "流式最短路径失败，转入现有 Agent 兜底链路。", 0L);
                        streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
                    })
                    .subscribe();
            disposableRef.set(disposable);
        } catch (Exception ex) {
            if (finished.compareAndSet(false, true)) {
                modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                sendTrace(emitter, context, "调用模型", "model", "failed",
                        chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), System.currentTimeMillis() - startedAt);
                if (streamInterruptedPartialAnswer(context, fullAnswer, ex, lockToken, lockReleased, emitter)) {
                    return;
                }
                sendTrace(emitter, context, "降级处理", "fallback", "started",
                        "流式最短路径启动失败，转入现有 Agent 兜底链路。", 0L);
                streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
            }
        }
    }

    private void streamImmediateAnswer(ChatContext context,
                                       ChatExecutionResult executionResult,
                                       SseEmitter emitter) {
        String answer = resolveFinalAnswer(context, executionResult);
        sendAnswerChunks(emitter, answer);
        chatResultPersister.persist(context, answer, executionResult);
    }

    private void streamAgentRuntimeAnswer(ChatContext context,
                                          String lockToken,
                                          AtomicBoolean lockReleased,
                                          SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        try {
            sendTrace(emitter, context, "选择能力", "agent", "started",
                    "进入统一 AgentRuntimeEngine。", 0L);
            sendStatus(emitter, "正在执行 Agent Runtime");
            AgentRun run = agentRuntimeEngine.run(context);
            emitAgentRunEvents(emitter, context, run);
            ChatExecutionResult executionResult = run.executionResult();
            String answer = resolveFinalAnswer(context, executionResult);
            sendAnswerChunks(emitter, answer);
            chatResultPersister.persist(context, answer, executionResult);
            sendTrace(emitter, context, "完成", "final", "success",
                    "Agent Runtime 已完成。", System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.warn("AgentRuntimeEngine 执行失败，sessionKey={}, reason={}", context.session().getSessionKey(), ex.getMessage());
            sendTrace(emitter, context, "执行 Agent Runtime", "agent", "failed",
                    chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), System.currentTimeMillis() - startedAt);
            streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
            return;
        }
        releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
        completeEmitter(emitter);
    }

    private void emitAgentRunEvents(SseEmitter emitter, ChatContext context, AgentRun run) {
        if (run == null) {
            return;
        }
        for (CapabilityResult result : run.capabilityResults()) {
            String eventName = "skills".equalsIgnoreCase(result.toolset()) ? "skill_call" : "tool_call";
            sendCapabilityCall(emitter, eventName, context.requestId(), result);
            sendTrace(emitter,
                    context,
                    result.capabilityId(),
                    eventName.equals("skill_call") ? "skill" : "tool",
                    normalizeCapabilityStatus(result.status()),
                    result.summary() + " · " + truncateForEvent(result.payload(), 320),
                    result.durationMs());
        }
        sendVerification(emitter, context.requestId(), run.verification());
        if (run.verification() != null) {
            sendTrace(emitter,
                    context,
                    "校验证据",
                    "verification",
                    run.verification().status(),
                    run.verification().summary(),
                    0L);
        }
    }

    private void streamModelLedAnswer(ChatContext context,
                                      String lockToken,
                                      AtomicBoolean lockReleased,
                                      SseEmitter emitter,
                                      AtomicReference<Disposable> disposableRef) {
        long startedAt = System.currentTimeMillis();
        sendTrace(emitter, context, "选择能力", "agent", "success",
                "模型主导流式链路，可按需调用工具。", 0L);
        sendTrace(emitter, context, "调用模型", "model", "started",
                context.activeClient().displayName(), 0L);
        sendStatus(emitter, "正在调用模型并实时输出");
        StringBuilder fullAnswer = new StringBuilder();
        AiProviderService.ActiveChatClient streamClient = context.activeClient();
        final org.springframework.ai.chat.model.ChatResponse[] latestStreamResponse = new org.springframework.ai.chat.model.ChatResponse[1];
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
                        sendTrace(emitter, context, "调用模型", "model", "success",
                                streamClient.displayName(), System.currentTimeMillis() - startedAt);
                        sendTrace(emitter, context, "完成", "final", "success",
                                "已生成最终回答。", System.currentTimeMillis() - startedAt);
                        releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                        completeEmitter(emitter);
                    })
                    .doOnError(ex -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        closeToolScope(toolScopeRef);
                        modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                        sendTrace(emitter, context, "调用模型", "model", "failed",
                                chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), System.currentTimeMillis() - startedAt);
                        if (streamInterruptedPartialAnswer(context, fullAnswer, ex, lockToken, lockReleased, emitter)) {
                            return;
                        }
                        sendTrace(emitter, context, "降级处理", "fallback", "started",
                                "模型主导流式链路失败，转入现有 Agent 兜底链路。", 0L);
                        streamBlockingFallback(context, ex, lockToken, lockReleased, emitter);
                    })
                    .doFinally(signalType -> closeToolScope(toolScopeRef))
                    .subscribe();
            disposableRef.set(disposable);
        } catch (Exception ex) {
            if (finished.compareAndSet(false, true)) {
                closeToolScope(toolScopeRef);
                modelTransportGuardService.markFailure(streamClient.providerId(), ex);
                sendTrace(emitter, context, "调用模型", "model", "failed",
                        chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), System.currentTimeMillis() - startedAt);
                if (streamInterruptedPartialAnswer(context, fullAnswer, ex, lockToken, lockReleased, emitter)) {
                    return;
                }
                sendTrace(emitter, context, "降级处理", "fallback", "started",
                        "模型主导流式链路启动失败，转入现有 Agent 兜底链路。", 0L);
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
            sendTrace(emitter, context, "降级处理", "fallback", "success",
                    "已通过现有 Agent 链路整理结果。", 0L);
        } catch (Exception fallbackEx) {
            log.warn("流式降级回答失败，sessionKey={}, reason={}", context.session().getSessionKey(), fallbackEx.getMessage());
            sendTrace(emitter, context, "降级处理", "fallback", "failed",
                    chatResponsePolicyService.simplifyFailureReason(fallbackEx.getMessage()), 0L);
            sendError(emitter, fallbackEx.getMessage());
        } finally {
            releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
            sendTrace(emitter, context, "完成", "final", "success", "流式请求已结束。", 0L);
            completeEmitter(emitter);
        }
    }

    boolean shouldUseModelLedStreaming(ChatContext context) {
        return context != null
                && modelLedStreamingEnabled
                && "agent".equals(normalizeResponseMode(context.responseMode()))
                && !isGeneralDecision(context)
                && !"control-plane".equalsIgnoreCase(safe(context.intent()))
                && !"model_control".equalsIgnoreCase(safe(context.intent()))
                && !"local-files".equalsIgnoreCase(safe(context.intent()))
                && !"local_files".equalsIgnoreCase(safe(context.intent()))
                && !requiresBackendCapabilityExecution(context)
                && useSimplifiedMode(context.executionMode())
                && modelTransportGuardService.isModelCallEnabled(context.activeClient());
    }

    boolean shouldUseAgentRuntime(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        return agentRuntimeEngine != null
                && decision != null
                && !decision.isGeneral()
                && !decision.requiresConfirmation()
                && !decision.isDangerous();
    }

    boolean shouldUseBasicModelStreaming(ChatContext context) {
        String responseMode = normalizeResponseMode(context == null ? null : context.responseMode());
        return context != null
                && basicStreamingEnabled
                && useSimplifiedMode(context.executionMode())
                && ("agent".equals(responseMode) || "fast".equals(responseMode))
                && "general".equalsIgnoreCase(safe(context.intent()))
                && modelTransportGuardService.isModelCallEnabled(context.activeClient());
    }

    private boolean requiresBackendCapabilityExecution(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        if (decision == null || decision.isGeneral()) {
            return false;
        }
        String executionPath = safe(decision.executionPath());
        return "agent_tools".equalsIgnoreCase(executionPath)
                || "skill_direct".equalsIgnoreCase(executionPath)
                || "task_draft".equalsIgnoreCase(executionPath);
    }

    private boolean isGeneralDecision(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        return decision == null || decision.isGeneral();
    }

    private String renderBasicChatPrompt(AssembledContext assembled) {
        return """
                用户问题：
                %s

                直接回答用户问题，保持中文自然、完整、有重点。
                这是普通聊天路径：不要调用工具，不要输出内部阶段名，不要提到路由、OPAR、兜底或实现细节。
                如果问题本身需要外部文件、网页、项目源码或实时数据，简洁说明需要进入 Agent 工具链路。
                """.formatted(safe(assembled == null ? null : assembled.question()));
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

    private String summarizeExecution(ChatExecutionResult executionResult) {
        if (executionResult == null) {
            return "执行完成，但没有返回执行摘要。";
        }
        String action = safe(executionResult.action());
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
                    sendTrace(emitter, context, "模型整理", "model", "success",
                            reflectClient.displayName(), 0L);
                    sendTrace(emitter, context, "完成", "final", "success", "已生成最终回答。", 0L);
                    releaseSessionLockOnce(context.session().getSessionKey(), lockToken, lockReleased);
                    completeEmitter(emitter);
                })
                .doOnError(ex -> {
                    modelTransportGuardService.markFailure(reflectClient.providerId(), ex);
                    sendTrace(emitter, context, "模型整理", "model", "failed",
                            chatResponsePolicyService.simplifyFailureReason(ex.getMessage()), 0L);
                    String fallback = chatResponsePolicyService.buildPartialAnswerFromAction(executionResult.action(), ex.getMessage());
                    if (!StringUtils.hasText(fallback)) {
                        fallback = metaGuardExecutor.fallbackAnswer(modelTransportGuardService.disabledModelReason(reflectClient), context.assembled());
                    }
                    fullAnswer.setLength(0);
                    fullAnswer.append(fallback);
                    sendAnswerChunks(emitter, fallback);
                    chatResultPersister.persist(context, fullAnswer.toString(), executionResult);
                    sendTrace(emitter, context, "完成", "final", "failed", "已返回降级结果。", 0L);
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
        if (shouldUseAgentRuntime(context)) {
            AgentRun run = agentRuntimeEngine.run(context);
            persistAgentRunTrace(context, run);
            return run.executionResult();
        }
        return useSimplifiedMode(context.executionMode())
                ? simplifiedOparEngine.run(context.activeClient(), context.systemPrompt(), context.assembled(), context.requestId(), metaGuardExecutor::fallbackAnswer, context.decision())
                : oparLoopEngine.runLoop(context.activeClient(), context.systemPrompt(), context.assembled(), context.requestId(), metaGuardExecutor::fallbackAnswer, context.decision());
    }

    private void persistAgentRunTrace(ChatContext context, AgentRun run) {
        if (agentRunTraceService == null || context == null || run == null) {
            return;
        }
        for (CapabilityResult result : run.capabilityResults()) {
            agentRunTraceService.record(
                    context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.requestId(),
                    result.capabilityId(),
                    "skills".equalsIgnoreCase(result.toolset()) ? "skill" : "tool",
                    normalizeCapabilityStatus(result.status()),
                    result.summary() + " · " + truncateForEvent(result.payload(), 320),
                    result.durationMs()
            );
        }
        if (run.verification() != null) {
            agentRunTraceService.record(
                    context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.requestId(),
                    "校验证据",
                    "verification",
                    run.verification().status(),
                    run.verification().summary(),
                    0L
            );
        }
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

    private void sendDecision(SseEmitter emitter, ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        if (decision == null) {
            return;
        }
        String capabilities = decision.selectedCapabilities() == null
                ? ""
                : String.join(",", decision.selectedCapabilities());
        String payload = """
                {"requestId":"%s","intent":"%s","executionPath":"%s","selectedCapabilities":"%s","riskLevel":"%s","requiresConfirmation":%s,"reason":"%s"}
                """.formatted(
                jsonEscape(context.requestId()),
                jsonEscape(decision.intent()),
                jsonEscape(decision.executionPath()),
                jsonEscape(capabilities),
                jsonEscape(decision.riskLevel()),
                decision.requiresConfirmation(),
                jsonEscape(decision.reason())
        ).trim();
        sendEvent(emitter, "decision", payload);
    }

    private void sendActionRequired(SseEmitter emitter, AgentActionProposal proposal) {
        if (proposal == null) {
            return;
        }
        String payload = """
                {"proposalId":"%s","requestId":"%s","actionType":"%s","title":"%s","summary":"%s","riskLevel":"%s","expiresAt":%d,"status":"%s"}
                """.formatted(
                jsonEscape(proposal.proposalId()),
                jsonEscape(proposal.requestId()),
                jsonEscape(proposal.actionType()),
                jsonEscape(proposal.title()),
                jsonEscape(proposal.summary()),
                jsonEscape(proposal.riskLevel()),
                proposal.expiresAt(),
                jsonEscape(proposal.status())
        ).trim();
        sendEvent(emitter, "action_required", payload);
    }

    private void sendCapabilityCall(SseEmitter emitter,
                                    String eventName,
                                    String requestId,
                                    CapabilityResult result) {
        if (result == null) {
            return;
        }
        String payload = """
                {"requestId":"%s","capabilityId":"%s","toolset":"%s","status":"%s","summary":"%s","durationMs":%d,"riskLevel":"%s","payload":"%s"}
                """.formatted(
                jsonEscape(requestId),
                jsonEscape(result.capabilityId()),
                jsonEscape(result.toolset()),
                jsonEscape(result.status()),
                jsonEscape(result.summary()),
                result.durationMs(),
                jsonEscape(result.riskLevel()),
                jsonEscape(truncateForEvent(result.payload(), 1200))
        ).trim();
        sendEvent(emitter, eventName, payload);
    }

    private void sendVerification(SseEmitter emitter, String requestId, VerificationResult verification) {
        if (verification == null) {
            return;
        }
        String payload = """
                {"requestId":"%s","status":"%s","sufficient":%s,"summary":"%s"}
                """.formatted(
                jsonEscape(requestId),
                jsonEscape(verification.status()),
                verification.sufficient(),
                jsonEscape(verification.summary())
        ).trim();
        sendEvent(emitter, "verification", payload);
    }

    private void sendTrace(SseEmitter emitter,
                           String requestId,
                           String stepName,
                           String type,
                           String status,
                           String detail,
                           long durationMs) {
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                safe(requestId),
                safe(stepName),
                safe(type),
                safe(status),
                safe(detail),
                Math.max(0L, durationMs),
                System.currentTimeMillis()
        );
        String payload = renderTracePayload(event);
        sendEvent(emitter, "trace", payload);
    }

    private void sendTrace(SseEmitter emitter,
                           ChatContext context,
                           String stepName,
                           String type,
                           String status,
                           String detail,
                           long durationMs) {
        if (context == null) {
            sendTrace(emitter, "", stepName, type, status, detail, durationMs);
            return;
        }
        AgentRunTraceEvent event = agentRunTraceService == null
                ? new AgentRunTraceEvent(context.requestId(), stepName, type, status, safe(detail), Math.max(0L, durationMs), System.currentTimeMillis())
                : agentRunTraceService.record(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.requestId(),
                stepName,
                type,
                status,
                detail,
                durationMs
        );
        sendEvent(emitter, "trace", renderTracePayload(event));
    }

    private String renderTracePayload(AgentRunTraceEvent event) {
        return """
                {"requestId":"%s","stepName":"%s","type":"%s","status":"%s","detail":"%s","durationMs":%d,"timestamp":%d}
                """.formatted(
                jsonEscape(event.requestId()),
                jsonEscape(event.stepName()),
                jsonEscape(event.type()),
                jsonEscape(event.status()),
                jsonEscape(event.detail()),
                Math.max(0L, event.durationMs()),
                event.timestamp()
        ).trim();
    }

    private String normalizeCapabilityStatus(String status) {
        return "failed".equalsIgnoreCase(status) ? "failed" : "success";
    }

    private String truncateForEvent(String text, int limit) {
        String value = text == null ? "" : text.trim();
        int safeLimit = Math.max(80, limit);
        return value.length() <= safeLimit ? value : value.substring(0, safeLimit) + "...";
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
