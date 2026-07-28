package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
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
 * Reflexion 范式引擎 — 执行→反思→改进重试(+ memory 累积)。
 * <p>
 * 与 {@link ReActEngine}(Thought-Action-Observation 边推理边行动)和 {@link PlanExecuteEngine}(先规划再执行)
 * 不同,Reflexion 把"反思"显式化为一等阶段:Actor 产出答案 → Evaluator 评估打分/指出缺陷 →
 * Reflector 据反馈生成改进意见注入下一轮 Actor,直到答案合格或达 {@code max-reflections}——
 * 把"自我纠错"作为循环骨架,适合需要多轮打磨的质量敏感任务。
 * </p>
 * <p>
 * <b>当前状态:RX-T1 骨架</b>——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#REFLECTION}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == REFLECTION} 时为真;</li>
 *   <li>{@code execute()}/{@code stream()} <b>占位</b>——循环体(Actor→Evaluator→Reflector + 假完成守护
 *       + memory 累积)留待 RX-T3 填充。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine}/{@link PlanExecuteEngine} 的 11 bean 依赖 + {@link ExplicitToolExecutioner}
 * (RX-T3 反思循环手动执行工具时复用)+ {@code max-reflections} 配置(clamp 到 [1,5])。
 * </p>
 */
@Service
public class ReflexionEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(ReflexionEngine.class);

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
    private final int maxReflections;

    public ReflexionEngine(AiProviderService aiProviderService,
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
                           @Value("${springclaw.chat.max-reflections:3}") int maxReflections) {
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
        this.maxReflections = Math.max(1, Math.min(maxReflections, 5));
    }

    @Override
    public String name() {
        return "reflexion-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.REFLECTION;
    }

    @Override
    public int priority() {
        return 8;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.REFLECTION;
    }

    /**
     * 阻塞执行入口——<b>RX-T1 占位</b>:Actor→Evaluator→Reflector 循环体留待 RX-T3 填充。
     * 当前返回降级 {@link ChatExecutionResult}(本地技能兜底,对齐 {@link ReActEngine}/{@link PlanExecuteEngine}
     * 模型不可用降级路径),保证占位阶段不炸、不静默走错引擎。
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        log.debug("Reflexion execute 占位(RX-T3 循环未实现): requestId={}", ctx.requestId());
        AssembledContext assembled = ctx.assembled();
        LocalSkillFallbackService.LocalSkillResult fallback =
                localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
        return new ChatExecutionResult(
                observePrompt(ctx),
                "Reflexion 循环尚未实现(RX-T3 填充)",
                fallback != null ? fallback.executionDetails() : "Reflexion 引擎骨架占位",
                fallback != null ? fallback.fallbackAnswer() : "",
                false
        );
    }

    /**
     * 流式执行入口——<b>RX-T1 占位</b>:完整 SSE 生命周期(trace → 反思循环 → persist → complete,
     * 对齐 {@link ReActEngine}/{@link PlanExecuteEngine})留待 RX-T3 填充。当前直接返回 null,
     * 不发射任何事件,避免误用占位路径产生半成品流。
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        log.debug("Reflexion stream 占位(RX-T3 循环未实现): requestId={}", context.requestId());
        return null;
    }

    private String observePrompt(ChatContext ctx) {
        return ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
    }
}
