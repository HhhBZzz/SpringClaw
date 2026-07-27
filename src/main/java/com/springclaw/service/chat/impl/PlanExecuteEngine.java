package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
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
 * <b>当前状态:PE-T4 Execute + Replan + 假完成守护 + 流式 SSE 已实现</b>——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#PLAN_EXECUTE}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == PLAN_EXECUTE} 时为真;</li>
 *   <li>{@link #runPlan}(PE-T3):BeanOutputConverter&lt;{@link Plan}&gt; 结构化输出——一次模型调用
 *       产出有序 {@code List<PlanStep>},{@link #renderPlanPrompt} 注入 format 强制 JSON 输出;</li>
 *   <li>{@link #stream} / {@link #execute} / {@link #runPlanExecute}(<b>PE-T4 新增</b>):Plan→Execute→Replan
 *       主循环——每次循环开头 {@link #runPlan} 产出计划,{@link #runPlanExecute} 逐步 Execute
 *       ({@link #callLlmForStep} 让 LLM 据当前 plan 步 + 历史 → Thought+Action 文本,经共享
 *       {@link ExplicitToolExecutioner} 解析并手动执行工具,Observation 回灌历史);
 *       步骤失败({@link #stepFailed})或假完成({@link AutonomousExecutionTracker} 校验 write/
 *       side_effect/dangerous 工具证据)→ 带 feedback 的 Replan({@code max-replan} 兜底)。</li>
 *   <li>流式 SSE 生命周期(对齐 {@link ReActEngine#stream}):trace(started) → 循环 → answerChunks →
 *       persist(TERMINAL_RESULT) → reportResult → trace(success) → releaseLockOnce → completeEmitter;
 *       异常委托 {@code fallbackHandler}。每步 Thought/Action/Observation 三段式 trace。</li>
 *   <li>{@link ChatExecutionResult}/trace 精细化投影留 PE-T5(当前 {@link #finalResult} 基础版:
 *       observe / plan="Plan-Execute 执行 N 步计划" / action=每步轨迹 / reflect=末步答案+步骤概要 /
 *       modelEnabled)。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine} 的 11 bean 依赖 + {@link ExplicitToolExecutioner}(PE-T1 提取)
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
    private final ExplicitToolExecutioner explicitToolExecutioner;
    private final int maxReplan;

    /**
     * Plan 阶段结构化输出转换器——把 LLM 输出反序列化为 {@link Plan} wrapper。
     * <p>对齐 {@link OparLoopEngine#planOutputConverter} 的 BeanOutputConverter 模式;
     * {@link #renderPlanPrompt} 注入 {@code getFormat()} 强制结构化输出,{@link #runPlan}
     * 经 {@code .responseEntity(Plan.class)} 取回实体。</p>
     * <p>Java 泛型擦除——不能直接用 {@code BeanOutputConverter<List<PlanStep>>}(元素类型在运行期丢失),
     * 故包一层 {@link Plan} wrapper record(与 OparLoopEngine 的 {@link PlanResult} 单对象模式同构)。</p>
     */
    private final BeanOutputConverter<Plan> planOutputConverter = new BeanOutputConverter<>(Plan.class);

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
                             ExplicitToolExecutioner explicitToolExecutioner,
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
        this.explicitToolExecutioner = explicitToolExecutioner;
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
     * 阻塞执行入口——内部跑 {@link #runPlanExecute}(无 emitter)。
     * <p>结构对齐 {@link ReActEngine#execute}:直接进入 Plan→Execute→Replan 循环,返回 {@link ChatExecutionResult}。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        return runPlanExecute(ctx, null, ctx.requestId());
    }

    /**
     * 流式执行入口——管理完整 SSE 生命周期,内部跑 {@link #runPlanExecute}。
     * <p>结构对齐 {@link ReActEngine#stream}:trace(started) → Plan→Execute→Replan 循环 →
     * 发送最终答案 → persist(TERMINAL_RESULT) → reportResult → trace(success) → releaseLockOnce →
     * completeEmitter;异常委托 {@code fallbackHandler}。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        try {
            sseEventBridge.sendTrace(emitter, context, "Plan-Execute 循环", "plan-execute", "started",
                    "进入 Plan-Execute(先规划再执行,可重规划)循环。", 0L);
            sseEventBridge.sendStatus(emitter, "Plan-Execute 规划执行中");

            ChatExecutionResult result = runPlanExecute(context, emitter, context.requestId());

            String finalAnswer = resolveFinalAnswer(result);
            sseEventBridge.sendAnswerChunks(emitter, finalAnswer);
            chatResultPersister.persist(context, finalAnswer, result, ChatPersistenceIntent.TERMINAL_RESULT);
            reportResult(context, result, finalAnswer);

            sseEventBridge.sendTrace(emitter, context, "Plan-Execute 循环", "plan-execute", "success",
                    "Plan-Execute 循环执行完成(" + result.plan() + ")。", 0L);
            sseEventBridge.sendTrace(emitter, context, "完成", "final", "success",
                    "已生成最终回答。", 0L);
            releaseLockOnce(context, lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        } catch (Exception ex) {
            log.warn("Plan-Execute SSE 执行失败: sessionKey={}, reason={}",
                    context.assembled() == null ? "?" : context.assembled().sessionKey(), ex.getMessage());
            try {
                String simplifiedReason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
                sseEventBridge.sendTrace(emitter, context, "Plan-Execute 循环", "plan-execute", "failed",
                        simplifiedReason, 0L);
            } catch (Exception ignored) {}
            fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
        }
        return null;
    }

    // === PE-T4: Plan → Execute → Replan 主循环(假完成守护 + 流式 SSE) ===

    /**
     * Plan-Execute 核心循环——每次循环开头 {@link #runPlan} 产出计划,逐步 Execute
     * ({@link #callLlmForStep} + {@link ExplicitToolExecutioner} 手动执行工具),观察结果后:
     * <ul>
     *   <li>步骤失败({@link #stepFailed}:工具执行异常/未找到/解析失败)→ 带 feedback 的 Replan;</li>
     *   <li>计划执行完无失败 → 假完成守护({@link AutonomousExecutionTracker}):read 直接完成,
     *       write/side_effect/dangerous 必须校验工具证据,无证据则拒绝并带 rejection Replan;</li>
     *   <li>Replan 次数达 {@code max-replan} → 兜底返回当前最佳结果。</li>
     * </ul>
     * <p>结构对齐 {@link ReActEngine#runReActLoop}:模型不可用降级 → 选工具 → tracker + scope →
     * 循环 → 假完成守护(参考 AutonomousLoop L317-345)。差异:Plan-Execute 先 Plan 再逐步 Execute,
     * 完成判定在 plan 全部步执行完后(非逐步终止);Replan 把失败/假完成 feedback 注入下一次 {@link #runPlan}。</p>
     */
    private ChatExecutionResult runPlanExecute(ChatContext ctx, SseEmitter emitter, String requestId) {
        AiProviderService.ActiveChatClient activeClient = ctx.activeClient();
        AssembledContext assembled = ctx.assembled();
        AgentDecision decision = ctx.decision();
        if (requestId == null) requestId = ctx.requestId();
        String riskLevel = decision != null ? decision.riskLevel() : "read";

        // 模型不可用 → 降级(对齐 ReActEngine.runReActLoop L206-216)
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    modelTransportGuardService.disabledModelPlanReason(activeClient),
                    fallback != null ? fallback.executionDetails() : modelTransportGuardService.disabledModelActionReason(activeClient),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        }

        final Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), decision);
        final boolean allowFailover = isSafeToRetry(tools);
        // 执行追踪器 — 每次 Replan 复用同一 tracker,累积真实工具证据(对齐
        // AutonomousLoopEngine L221 / ReActEngine L226)。假完成守护用它校验 write/side_effect/dangerous。
        final AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
        // 工具执行上下文 scope —— 让 Execute 阶段反射调用的工具方法经 ToolRuntimeAspect 时
        // 能读到 userId/sessionKey/runId 等做权限检查 + 审计 + emit TOOL_* 事件(对齐
        // AutonomousLoopEngine L223-235 / ReActEngine L231-239)。scope 内 setTracker,finally clearTracker。
        ToolExecutionContext toolContext = new ToolExecutionContext(
                assembled == null ? null : assembled.sessionKey(),
                ctx.channel(),
                ctx.userId(),
                requestId,
                "PLAN_EXECUTE",
                requestId,
                ctx.roleCode()
        );

        List<PlanStep> plan = new ArrayList<>();
        List<ExecuteStep> executedSteps = new ArrayList<>();
        String lastFeedback = "";

        try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
            ToolExecutionContextHolder.setTracker(tracker);

            for (int replanNo = 0; replanNo <= maxReplan; replanNo++) {
                // (Re-)Plan:每次循环开头产出计划(首次 feedback 为空,重规划带失败/假完成反馈)
                plan = runPlan(ctx, activeClient, tools, lastFeedback);
                if (plan.isEmpty()) {
                    log.warn("PlanExecute plan 为空,终止循环: requestId={}, replanNo={}", requestId, replanNo);
                    break;
                }
                if (emitter != null) {
                    try {
                        sseEventBridge.sendTrace(emitter, ctx,
                                "Plan-Execute Plan(第 " + (replanNo + 1) + " 次)", "plan-execute", "plan",
                                "生成 " + plan.size() + " 步计划"
                                        + (replanNo == 0 ? "" : "(Replan #" + replanNo + ")"), 0L);
                        sseEventBridge.sendStatus(emitter,
                                "Plan-Execute 执行中(第 " + (replanNo + 1) + " 次规划)");
                    } catch (Exception ignored) {}
                }

                // Execute 逐步:LLM 据 stepText + history → Thought+Action 文本,引擎手动执行工具
                String history = "";
                boolean failed = false;
                int planSize = plan.size();
                for (int i = 0; i < planSize; i++) {
                    PlanStep step = plan.get(i);
                    int stepIndex = i + 1;
                    log.info("PlanExecute 步骤 {}/{}: requestId={}, replanNo={}, riskLevel={}",
                            stepIndex, planSize, requestId, replanNo, riskLevel);
                    if (emitter != null) {
                        try {
                            sseEventBridge.sendStatus(emitter, "Execute 步 " + stepIndex + "/" + planSize);
                        } catch (Exception e) {
                            log.warn("SSE 进度事件发送失败(可能客户端已断开): stepIndex={}", stepIndex);
                        }
                    }

                    ModelCallExecutor.ModelCallResult<String> stepResult = callLlmForStep(
                            ctx, step, stepIndex, planSize, history, tools, activeClient, requestId, allowFailover);
                    activeClient = stepResult.client(); // failover 后更新
                    String thought = stepResult.value();

                    boolean hasAction = explicitToolExecutioner.hasActionLine(thought);
                    String observation = hasAction
                            ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
                    executedSteps.add(new ExecuteStep(step, thought, observation));
                    history += buildStepHistory(step, thought, observation, stepIndex);

                    if (emitter != null) {
                        try {
                            // Execute 三段式 trace — 每步 Thought/Action/Observation(模仿 ReActEngine L305-313)
                            sseEventBridge.sendTrace(emitter, ctx, "Plan-Execute Thought " + stepIndex,
                                    "plan-execute", "thought", TextUtils.truncate(thought, 200), 0L);
                            if (hasAction) {
                                sseEventBridge.sendTrace(emitter, ctx, "Plan-Execute Action " + stepIndex,
                                        "plan-execute", "action",
                                        TextUtils.truncate(explicitToolExecutioner.describeAction(thought, true), 200), 0L);
                                sseEventBridge.sendTrace(emitter, ctx, "Plan-Execute Observation " + stepIndex,
                                        "plan-execute", "observation", TextUtils.truncate(observation, 200), 0L);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (stepFailed(observation, thought, hasAction)) {
                        failed = true;
                        log.warn("PlanExecute 步骤 {} 失败,触发 Replan: requestId={}, observation={}",
                                stepIndex, requestId, TextUtils.truncate(observation, 200));
                        if (emitter != null) {
                            try {
                                sseEventBridge.sendTrace(emitter, ctx, "Plan-Execute 步骤失败",
                                        "plan-execute", "warning",
                                        "步骤 " + stepIndex + " 执行失败,触发 Replan: "
                                                + TextUtils.truncate(observation, 200), 0L);
                            } catch (Exception ignored) {}
                        }
                        break;
                    }
                }

                if (!failed) {
                    // 假完成守护(对齐 AutonomousLoopEngine L317-345 + ReActEngine L320-342):
                    // read 直接完成;write/side_effect/dangerous 必须校验 tracker 工具证据。
                    if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
                        log.info("PlanExecute 任务完成: requestId={}, replanNo={}, riskLevel={}, planSteps={}, "
                                        + "hasWrite={}, hasCmd={}, hasVerified={}",
                                requestId, replanNo, riskLevel, planSize,
                                tracker.hasWriteToolCall(), tracker.hasRunCommandCall(), tracker.hasVerifiedSideEffect());
                        String lastAnswer = executedSteps.isEmpty() ? ""
                                : executedSteps.get(executedSteps.size() - 1).thought();
                        return finalResult(ctx, plan, executedSteps, lastAnswer, true);
                    }
                    // 假完成 → Replan(带 rejection 反馈)
                    String rejection = tracker.renderFakeCompletionRejection(riskLevel);
                    log.warn("PlanExecute 假完成拦截: requestId={}, replanNo={}, riskLevel={}, hasWrite={}, hasCmd={}",
                            requestId, replanNo, riskLevel,
                            tracker.hasWriteToolCall(), tracker.hasRunCommandCall());
                    if (emitter != null) {
                        try {
                            sseEventBridge.sendTrace(emitter, ctx, "Plan-Execute 假完成拦截",
                                    "plan-execute", "warning",
                                    "计划执行完但缺少真实工具证据,Replan: "
                                            + TextUtils.truncate(rejection, 200), 0L);
                        } catch (Exception ignored) {}
                    }
                    lastFeedback = "上次计划已执行完,但缺少真实工具证据,假完成被拦截:\n"
                            + buildExecutedHistory(executedSteps) + "\n" + rejection
                            + "\n请调整 plan,确保步骤实际调用工具产生真实副作用。";
                    continue; // 下一次 replan(不调失败反馈分支)
                }

                // 步骤失败 → Replan(带失败反馈)
                lastFeedback = "上次执行有步骤失败,请调整 plan。上次执行反馈:\n"
                        + buildExecutedHistory(executedSteps)
                        + "\n请基于失败信息重新规划,避开导致失败的工具或做法。";
                if (emitter != null) {
                    try {
                        sseEventBridge.sendTrace(emitter, ctx, "Plan-Execute Replan #" + (replanNo + 1),
                                "plan-execute", "replan",
                                "步骤失败,带反馈重新规划。", 0L);
                    } catch (Exception ignored) {}
                }
            }

            // max-replan 兜底:返回当前最佳结果(对齐 ReActEngine L346-348 的 max-steps 兜底)
            log.info("PlanExecute 达到 max-replan: requestId={}, maxReplan={}, executedSteps={}",
                    requestId, maxReplan, executedSteps.size());
            return finalResult(ctx, plan, executedSteps,
                    "已达 max-replan(" + maxReplan + "),返回当前最佳结果", true);
        } catch (Exception ex) {
            log.warn("PlanExecute 循环执行失败: requestId={}, reason={}", requestId, ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    "Plan-Execute 异常终止: " + chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                    buildActionTrace(executedSteps),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        } finally {
            // scope close 只还原 ToolExecutionContext,不清 tracker ThreadLocal;
            // 手动 clear(对齐 AutonomousLoopEngine L387/390 / ReActEngine L363)。
            ToolExecutionContextHolder.clearTracker();
        }
    }

    /**
     * Execute 单步的 LLM 调用——不挂 {@code .tools()},系统 prompt({@link #renderExecutePrompt})
     * 要求 LLM 据当前 plan 步 stepText + 历史 → 输出 Thought+Action 文本(或综合答案)。
     * <p>返回 {@link ModelCallExecutor.ModelCallResult} 让调用方更新 failover 后的 client
     * (对齐 ReActEngine L257-286 的 LLM 调用模式)。经 {@link ExplicitToolExecutioner} 解析 Action。</p>
     */
    private ModelCallExecutor.ModelCallResult<String> callLlmForStep(ChatContext ctx,
                                                                     PlanStep step,
                                                                     int stepIndex,
                                                                     int planSize,
                                                                     String history,
                                                                     Object[] tools,
                                                                     AiProviderService.ActiveChatClient client,
                                                                     String requestId,
                                                                     boolean allowFailover) throws Exception {
        AssembledContext assembled = ctx.assembled();
        String sessionKey = assembled == null ? "" : assembled.sessionKey();
        String systemPrompt = renderExecutePrompt(ctx, step, stepIndex, planSize, history, tools);
        return modelCallExecutor.executeChat(
                client,
                "plan-execute-step-" + stepIndex,
                new ModelCallExecutor.ChatRequestContext(
                        requestId,
                        sessionKey,
                        ctx.channel(),
                        ctx.userId()
                ),
                allowFailover,
                c -> {
                    // 手动循环主路径:不挂 .tools()——LLM 文本输出 Thought + Action,
                    // 循环下方 ExplicitToolExecutioner 解析并手动执行工具(对齐 ReActEngine L268-282)。
                    var resp = conversationAdvisorSupport.apply(
                                    c.chatClient().prompt()
                                            .system(systemPrompt)
                                            .user(TypedContextPromptRenderer.question(ctx)),
                                    sessionKey,
                                    ctx.userId())
                            .call()
                            .chatResponse();
                    return new ModelCallExecutor.ChatOperationResult<>(
                            ModelCallExecutor.extractText(resp), resp);
                }
        );
    }

    /**
     * 渲染 Execute 单步的 system prompt——要求 LLM 执行当前 plan 步,输出 Thought(+ Action 或答案)。
     * <p>结构模仿 {@link ReActEngine#renderReActPrompt}:{{INJECTION}} + {{QUESTION}} + {{TOOLS}} +
     * 当前步骤(stepIndex/planSize/stepText)+ {{HISTORY}}(已执行步的 Thought/Observation)。
     * 差异:ReAct 让模型自主决定下一步,Plan-Execute 的步骤已由 Planner 固定,Executor 只需执行当前步。</p>
     */
    String renderExecutePrompt(ChatContext ctx, PlanStep step, int stepIndex, int planSize,
                               String history, Object[] tools) {
        String template = """
                {{INJECTION}}你是 Plan-Execute Agent 的 Executor,正在逐步执行已规划好的计划。

                # 用户问题
                {{QUESTION}}

                # 当前进度
                正在执行第 {{STEP_INDEX}}/{{PLAN_SIZE}} 步。

                # 当前步骤
                {{STEP_TEXT}}

                # 可用工具
                {{TOOLS}}

                # 执行协议
                先输出 Thought(简短分析当前步骤如何执行),再:
                  - 若该步骤需要调用工具 → 输出一行 Action: 工具名(参数名="值", ...),例如 Action: search(query="Spring AI")
                    工具会被引擎执行,结果作为本步骤的 Observation;若工具无参数则写 Action: 工具名()
                  - 若该步骤是综合/分析/总结(无需工具)→ 直接输出该步骤的答案文本(不输出 Action 视为本步骤完成)

                # 已执行步骤(历史)
                {{HISTORY}}

                # 现在输出当前步骤的 Thought(与 Action 或答案):
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{STEP_INDEX}}", String.valueOf(stepIndex))
                .replace("{{PLAN_SIZE}}", String.valueOf(planSize))
                .replace("{{STEP_TEXT}}", StringUtils.hasText(step.stepText()) ? step.stepText().trim() : "")
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools))
                .replace("{{HISTORY}}", StringUtils.hasText(history) ? history : "（第一步，暂无历史）");
    }

    /**
     * 把单个 Execute 步骤拼入历史(供下一步 prompt 的 {{HISTORY}} 累积)。
     * <p>对齐 {@link ReActEngine#buildReActHistory} 的三段式,差异:Plan-Execute 每个 ExecuteStep
     * 关联一个 {@link PlanStep}(含 stepText),历史显式标注步骤的规划文本便于 LLM 理解上下文。</p>
     */
    private String buildStepHistory(PlanStep step, String thought, String observation, int stepIndex) {
        return "Step " + stepIndex + " [" + TextUtils.truncate(step.stepText(), 120) + "]\n"
                + "Thought: " + TextUtils.truncate(thought, 400) + "\n"
                + "Observation: " + TextUtils.truncate(observation, 400) + "\n\n";
    }

    /**
     * 把所有已执行步骤拼成结构化历史(供 finalResult 的 reflect 步骤概要 / Replan feedback 使用)。
     */
    String buildExecutedHistory(List<ExecuteStep> steps) {
        if (steps == null || steps.isEmpty()) return "（未执行任何步骤）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            ExecuteStep s = steps.get(i);
            sb.append("Step ").append(i + 1).append(" [")
                    .append(TextUtils.truncate(s.step().stepText(), 120)).append("]\n")
                    .append("Thought: ").append(TextUtils.truncate(s.thought(), 400)).append("\n")
                    .append("Observation: ").append(TextUtils.truncate(s.observation(), 400)).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 步骤失败判定——触发 Replan。
     * <p>判定规则:</p>
     * <ul>
     *   <li>模型本轮无输出(thought 空)→ 失败;</li>
     *   <li>无 Action 行(综合步/答案步)→ 不判定工具失败(空 observation 正常);</li>
     *   <li>有 Action 行但 {@link ExplicitToolExecutioner#execute} 返回的错误说明(以 "(" 起首、
     *       含 "失败"/"未找到"/"无法解析")→ 工具执行失败。</li>
     * </ul>
     * <p>{@link ExplicitToolExecutioner} 解析失败/工具未找到/执行异常时不抛、返回括号包裹的错误说明
     * (保持循环推进),本方法据此识别并触发 Replan。</p>
     */
    boolean stepFailed(String observation, String thought, boolean hasAction) {
        if (!StringUtils.hasText(thought)) return true; // 模型无输出 → 步骤失败
        if (!hasAction) return false; // 综合步/答案步,无工具调用不判定失败
        if (!StringUtils.hasText(observation)) return false;
        // ExplicitToolExecutioner 错误说明以 "(" 起首(如 "(未找到工具: ...)" "(工具执行失败: ...)");
        // 加 startsWith("(") 前缀判定,避免合法 observation(含"未找到"/"失败"等关键词,如
        // search 返回"未找到相关文档"、grep 返回"0 失败")被误判为步骤失败,触发无谓 Replan。
        if (!observation.startsWith("(")) return false;
        return observation.contains("失败") || observation.contains("未找到") || observation.contains("无法解析");
    }

    /**
     * 构造终态 {@link ChatExecutionResult}(PE-T5 细化,模仿 ReActEngine L378-389)。
     * <ul>
     *   <li>observe = {@link #observePrompt}</li>
     *   <li>plan = "Plan-Execute 执行 N 步计划"</li>
     *   <li>action = {@link #buildActionTrace}(每步 stepText+Thought 摘要)</li>
     *   <li>reflect = 最终答案 + "\n步骤概要:\n" + {@link #buildExecutedHistory}(基础版:末步 LLM 输出作答案,
     *       PE-T5 细化为独立的综合 LLM 调用)</li>
     *   <li>modelEnabled = 形参</li>
     * </ul>
     */
    private ChatExecutionResult finalResult(ChatContext ctx, List<PlanStep> plan,
                                            List<ExecuteStep> steps, String finalAnswer, boolean modelEnabled) {
        String answer = StringUtils.hasText(finalAnswer) ? finalAnswer
                : (steps.isEmpty() ? "Plan-Execute 未产生最终答案。"
                        : TextUtils.truncate(steps.get(steps.size() - 1).thought(), 600));
        String reflect = answer + "\n步骤概要:\n" + buildExecutedHistory(steps);
        int planSteps = plan == null ? 0 : plan.size();
        return new ChatExecutionResult(
                observePrompt(ctx),
                "Plan-Execute 执行 " + planSteps + " 步计划",
                buildActionTrace(steps),
                reflect,
                modelEnabled
        );
    }

    /**
     * 把已执行步骤拼成 Action 轨迹(每步 stepText + Thought 摘要),作为 {@link ChatExecutionResult#action()}。
     * <p>模仿 {@link ReActEngine#buildActionTrace} 的 "[Step N] ..." 结构。</p>
     */
    private String buildActionTrace(List<ExecuteStep> steps) {
        if (steps == null || steps.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("\n");
        for (int i = 0; i < steps.size(); i++) {
            ExecuteStep s = steps.get(i);
            sj.add("[Step " + (i + 1) + "] " + TextUtils.truncate(s.step().stepText(), 120)
                    + " → Thought: " + TextUtils.truncate(s.thought(), 200));
        }
        return sj.toString();
    }

    /**
     * 从 {@link ChatExecutionResult} 取回用户可见的最终答案(模仿 ReActEngine L413-421)。
     * <p>优先 {@code reflect}(含末步答案 + 步骤概要);reflect 空时按 modelEnabled 给兜底语。</p>
     */
    private String resolveFinalAnswer(ChatExecutionResult result) {
        if (StringUtils.hasText(result.reflect())) {
            return result.reflect();
        }
        if (!result.modelEnabled()) {
            return "Plan-Execute 循环执行完成,但模型不可用。";
        }
        return "Plan-Execute 循环执行完成,共 " + result.plan() + "。";
    }

    /**
     * Execute 阶段单步的结构化轨迹——关联一个 {@link PlanStep}(规划文本)及其 Thought/Observation。
     * <p>对比 {@link ReActEngine.ReActStep}:Plan-Execute 的 Execute 步骤带有 Planner 产出的 stepText,
     * 让 finalResult 的 action/reflect 投影能同时呈现"规划了什么"与"执行了什么"。</p>
     */
    public record ExecuteStep(PlanStep step, String thought, String observation) {
    }

    private String observePrompt(ChatContext ctx) {
        return ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
    }

    private void reportResult(ChatContext context, ChatExecutionResult result, String answer) {
        if (lifecycleObserver == null) return;
        try {
            lifecycleObserver.resultReturned(context, result, answer, Instant.now());
        } catch (RuntimeException ex) {
            log.error("canonical lifecycle projection failed after plan-execute persistence, requestId={}",
                    context.requestId(), ex);
        }
    }

    private void releaseLockOnce(ChatContext context, String lockToken, AtomicBoolean lockReleased) {
        if (!lockReleased.compareAndSet(false, true) || lockToken == null) return;
        String sessionKey = context.assembled() == null ? null : context.assembled().sessionKey();
        if (sessionKey != null) {
            chatGuardService.releaseSessionLock(sessionKey, lockToken);
        }
    }

    /**
     * 是否允许同 provider 内 failover——含副作用型工具(写文件/脚本)时禁止重试,避免重复执行。
     * 复制自 {@link AutonomousLoopEngine}(L635-642)/ {@link ReActEngine}(L449-456)。
     */
    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null) return true;
        for (Object tool : tools) {
            if (tool instanceof com.springclaw.tool.pack.WorkspaceEditToolPack) return false;
            if (tool instanceof com.springclaw.tool.pack.ScriptSkillToolPack) return false;
        }
        return true;
    }

    // === PE-T3: Plan 阶段(BeanOutputConverter 结构化 List<PlanStep>)===

    /**
     * Plan 阶段产出的单个步骤——Execute 阶段(PE-T4)逐步消费。
     * <p>只有 {@code stepText} 一个字段:模型自然语言描述一个明确动作(检索/调用工具/分析/综合)。
     * 保持最小化,避免过早结构化动作类型/参数——具体执行交由 PE-T4 的 ExplicitToolExecutioner 解析。</p>
     */
    public record PlanStep(String stepText) {
    }

    /**
     * BeanOutputConverter 结构化输出 wrapper——包裹 {@code List<PlanStep>}。
     * <p>Java 泛型擦除下 {@code BeanOutputConverter<List<PlanStep>>} 无法保留元素类型,
     * 故用本 wrapper record 包一层(对齐 {@link OparLoopEngine} 的 {@link PlanResult} 单对象模式)。
     * Spring AI 的 BeanOutputConverter 支持反序列化 record + 嵌套 {@code List<PlanStep>}。</p>
     */
    public record Plan(List<PlanStep> steps) {
    }

    /**
     * Plan 阶段——一次模型调用,经 BeanOutputConverter 结构化产出有序 {@link PlanStep} 列表。
     * <p>结构对齐 {@link OparLoopEngine#runPlan} 的 BeanOutputConverter + {@code .responseEntity} 模式:
     * 渲染含 format 说明的 system prompt → {@code ModelCallExecutor.executeChat} 携
     * {@code .responseEntity(Plan.class)} → 取 {@code entity.steps()}。失败降级为空列表
     * (Execute 阶段 PE-T4 自行处理空计划/重规划/兜底,本方法不向调用方抛)。</p>
     * <p>{@code feedback} 非空表示重规划——携带上一次执行失败的反馈注入 prompt(首次为空)。</p>
     * <p>{@code client} 由调用方({@link #runPlanExecute})传入而非直接读 {@code ctx.activeClient()}——
     * 这样 Execute 步触发 failover 后更新的 {@code activeClient} local var 能跨 Plan/Execute/Replan
     * 传播,避免下一次 Replan 又回到原始(可能仍故障的)provider 重新发现 failover(对齐 ReAct 的
     * activeClient local var 模式)。</p>
     */
    List<PlanStep> runPlan(ChatContext ctx, AiProviderService.ActiveChatClient client,
                           Object[] tools, String feedback) {
        try {
            String systemPrompt = renderPlanPrompt(ctx, tools, feedback);
            ModelCallExecutor.ModelCallResult<Plan> callResult = modelCallExecutor.executeChat(
                    client,
                    "plan-execute-plan",
                    new ModelCallExecutor.ChatRequestContext(
                            ctx.requestId(),
                            ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                            ctx.channel(),
                            ctx.userId()
                    ),
                    true,
                    c -> {
                        var response = conversationAdvisorSupport.apply(
                                        c.chatClient().prompt()
                                                .system(systemPrompt)
                                                .user(TypedContextPromptRenderer.question(ctx)),
                                        ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                                        ctx.userId())
                                .call()
                                .responseEntity(Plan.class);
                        return new ModelCallExecutor.ChatOperationResult<>(
                                response.entity(),
                                response.response()
                        );
                    }
            );
            Plan plan = callResult.value();
            return plan == null || plan.steps() == null ? List.of() : plan.steps();
        } catch (Exception ex) {
            log.warn("Plan 阶段失败,降级为空计划: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 渲染 Plan 阶段的 system prompt——要求 LLM 把问题分解为有序可执行步骤,输出 Plan JSON。
     * <p>结构模仿 {@link ReActEngine#renderReActPrompt}:{{INJECTION}}({@link TypedContextPromptRenderer#promptPrefix})
     * + {{QUESTION}}({@link TypedContextPromptRenderer#question}) + {{TOOLS}}({@link ToolReflectionSupport#renderToolList})
     * + {{FEEDBACK}}(重规划时携带失败反馈,首次为空) + {{FORMAT}}({@link BeanOutputConverter#getFormat} 注入,
     * 强制结构化输出)。整体作 system prompt,用户问题单独作 user 消息(与 ReActEngine 一致)。</p>
     */
    String renderPlanPrompt(ChatContext ctx, Object[] tools, String feedback) {
        String template = """
                {{INJECTION}}你是 Plan-Execute Agent 的 Planner 阶段,请把用户问题分解为有序、可直接执行的步骤列表。

                # 用户问题
                {{QUESTION}}

                # 可用工具(执行阶段可调用)
                {{TOOLS}}

                # 反馈(重规划时携带上一次执行失败的反馈,首次为空)
                {{FEEDBACK}}

                # 输出要求
                1) 聚焦"规划"——只列出步骤,不实际执行;
                2) 每个步骤描述一个明确的动作(检索/调用工具/分析/综合总结);
                3) 步骤数量精简(通常 1-4 步),复杂问题可更多;
                4) 步骤有序、可独立执行,后一步可依赖前一步的结果;
                5) 输出必须严格遵循下面的格式说明,不要附加任何额外文本。

                # 输出格式说明
                {{FORMAT}}
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools))
                .replace("{{FEEDBACK}}", StringUtils.hasText(feedback) ? feedback.trim() : "（首次规划，暂无反馈）")
                .replace("{{FORMAT}}", planOutputConverter.getFormat());
    }
}
