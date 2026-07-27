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
 * Plan-Execute 范式引擎 — 先规划再执行(可重规划)。
 * <p>
 * 与 {@link ReActEngine}(Thought-Action-Observation 边推理边行动)不同,Plan-Execute 把"规划"
 * 与"执行"显式解耦:先让模型一次性产出结构化计划(步骤列表),再逐步执行(可调用工具),
 * 观察结果后允许有限次重规划——直到计划完成。适合步骤较确定、需先全局思考的多步任务。
 * </p>
 * <p>
 * <b>当前状态:PE-T2 骨架</b>——已接入地基(不再"未实现"),但循环体留空——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#PLAN_EXECUTE}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == PLAN_EXECUTE} 时为真;</li>
 *   <li>{@code execute} / {@code stream} <b>占位</b>(execute 返回降级 {@link ChatExecutionResult},
 *       stream 返回 {@code null})。<b>Plan/Execute 留 Task 3/4</b>:
 *       Task 3 填 Plan 阶段(BeanOutputConverter 结构化 {@code List<PlanStep>}),
 *       Task 4 填 Execute + Replan + 假完成守护 + SSE,届时复用共享
 *       {@link ExplicitToolExecutioner} / {@link ToolReflectionSupport}(PE-T1 已提取)。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine} 的 11 bean 依赖(剔除 Task 4 才接入的 {@link ExplicitToolExecutioner})
 * + {@code max-replan} 配置(clamp 到 [0,5])。
 * </p>
 */
@Service
public class PlanExecuteEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteEngine.class);

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
    private final int maxReplan;

    public PlanExecuteEngine(AiProviderService aiProviderService,
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
                             @Value("${springclaw.chat.max-replan:2}") int maxReplan) {
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
        this.maxReplan = Math.max(0, Math.min(maxReplan, 5));
    }

    @Override
    public String name() {
        return "plan-execute-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.PLAN_EXECUTE;
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.PLAN_EXECUTE;
    }

    /**
     * 阻塞执行入口——<b>占位</b>(Plan/Execute 留 Task 3/4)。
     * <p>当前仅返回降级 {@link ChatExecutionResult}:调用 {@code fallbackResponder} 给出兜底回答,
     * 标注"Plan-Execute 占位",不进入 Plan/Execute 循环。Task 3/4 将替换为真正的 Plan→Execute→Replan 循环
     * (复用 {@link ExplicitToolExecutioner} 手动执行工具 + 假完成守护,对齐 {@link ReActEngine#execute})。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        log.warn("PlanExecuteEngine.execute 占位实现(Plan/Execute 留 Task 3/4): requestId={}", ctx.requestId());
        String observe = ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
        String fallback = "";
        try {
            fallback = fallbackResponder.respond("plan-execute-placeholder", ctx.assembled());
        } catch (Exception ex) {
            log.warn("PlanExecuteEngine 占位 fallback 调用失败: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
        }
        return new ChatExecutionResult(
                observe,
                "Plan-Execute 占位(待 Task 3/4 实现)",
                "",
                fallback,
                false
        );
    }

    /**
     * 流式执行入口——<b>占位</b>(Plan/Execute 留 Task 3/4)。
     * <p>当前直接返回 {@code null}(不驱动 SSE 生命周期,不持久化)。Task 4 将填充完整的
     * Plan→Execute→Replan SSE 主路径(对齐 {@link ReActEngine#stream}:trace → 循环 → 发送最终答案 →
     * persist → reportResult → releaseLockOnce → completeEmitter,异常委托 {@code fallbackHandler})。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        log.warn("PlanExecuteEngine.stream 占位实现(Plan/Execute 留 Task 3/4): requestId={}",
                context.requestId());
        return null;
    }
}
