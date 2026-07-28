package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-Agent 范式引擎 — Coordinator→Worker 并行协作 + 结果聚合。
 * <p>
 * 与 {@link ReActEngine}(单 Agent 边推理边行动)、{@link PlanExecuteEngine}(单 Agent 先规划再执行)、
 * {@link ReflexionEngine}(单 Actor→Reflector 自我纠错)不同,Multi-Agent 把"多角色协作"显式化:
 * Coordinator 把用户问题 decompose 成若干子任务 → 并行分派给 Worker(每个 Worker 跑一次工具增强推理)→
 * Coordinator aggregate Worker 结果 → 必要时再 decompose(子任务未充分覆盖),直到答案合格或达
 * {@code max-agents}——把"分工与汇总"作为循环骨架,适合需要多视角/可拆解的复杂任务。
 * </p>
 * <p>
 * <b>当前状态:MA-T1 骨架(接入地基)——</b>
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#MULTI_AGENT}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == MULTI_AGENT} 时为真;</li>
 *   <li>{@code execute()}/{@code stream()} <b>占位</b>:返回降级 {@link ChatExecutionResult}(execute)/
 *       {@code null}(stream),不接入 Coordinator→Worker 循环——decompose/aggregate prompt 与
 *       runMultiAgentLoop 并行执行 + tracker 合并 + 假完成守护 由 MA-T2/T3 填充。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine}/{@link PlanExecuteEngine}/{@link ReflexionEngine} 的 11 bean 依赖 +
 * {@link ExplicitToolExecutioner}(MA-T3 Worker 手动执行工具时复用)+ {@code max-agents} 配置(clamp 到 [1,8])。
 * </p>
 */
@Service
public class MultiAgentEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentEngine.class);

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final LocalExecutionSupport localExecutionSupport;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final SseEventBridge sseEventBridge;
    private final ChatResultPersister chatResultPersister;
    private final ChatGuardService chatGuardService;
    private final RunLifecycleObserver lifecycleObserver;
    private final ExplicitToolExecutioner explicitToolExecutioner;
    private final int maxAgents;

    public MultiAgentEngine(AiProviderService aiProviderService,
                            ToolOrchestrator toolOrchestrator,
                            ModelTransportGuardService modelTransportGuardService,
                            ModelCallExecutor modelCallExecutor,
                            ConversationAdvisorSupport conversationAdvisorSupport,
                            LocalExecutionSupport localExecutionSupport,
                            ChatResponsePolicyService chatResponsePolicyService,
                            SseEventBridge sseEventBridge,
                            ChatResultPersister chatResultPersister,
                            ChatGuardService chatGuardService,
                            RunLifecycleObserver lifecycleObserver,
                            ExplicitToolExecutioner explicitToolExecutioner,
                            @Value("${springclaw.chat.max-agents:5}") int maxAgents) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.localExecutionSupport = localExecutionSupport;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.sseEventBridge = sseEventBridge;
        this.chatResultPersister = chatResultPersister;
        this.chatGuardService = chatGuardService;
        this.lifecycleObserver = lifecycleObserver;
        this.explicitToolExecutioner = explicitToolExecutioner;
        this.maxAgents = Math.max(1, Math.min(maxAgents, 8));
    }

    @Override
    public String name() {
        return "multi-agent-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.MULTI_AGENT;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.MULTI_AGENT;
    }

    /**
     * 阻塞执行入口(<b>MA-T1 占位</b>)——Coordinator→Worker 并行循环由 MA-T3 实现。
     * 当前返回降级 {@link ChatExecutionResult}(modelEnabled=false),不接入多智能体循环。
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        log.warn("MultiAgentEngine.execute 占位: 多智能体循环待 MA-T3 实现, requestId={}",
                ctx == null ? "null" : ctx.requestId());
        return new ChatExecutionResult(
                observePrompt(ctx),
                "多智能体循环(待 MA-T3 实现)",
                "(占位:Coordinator→Worker 并行协作循环未接入)",
                "多智能体循环尚未实现,暂未产出答案。",
                false
        );
    }

    /**
     * 流式执行入口(<b>MA-T1 占位</b>)——多智能体 SSE 生命周期由 MA-T3 实现。当前直接返回 {@code null}。
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        log.warn("MultiAgentEngine.stream 占位: 多智能体循环待 MA-T3 实现, requestId={}",
                context == null ? "null" : context.requestId());
        return null;
    }

    private String observePrompt(ChatContext ctx) {
        return ctx == null || ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
    }
}
