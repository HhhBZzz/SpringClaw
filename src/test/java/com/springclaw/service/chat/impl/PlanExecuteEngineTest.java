package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanExecuteEngine 单测(PE-T2 骨架 + PE-T3 Plan 阶段 + PE-T4 Execute/Replan/假完成守护)。
 * <p>PE-T4 新增:Execute 逐步(ExplicitToolExecutioner 手动执行工具)+ Replan(失败/假完成反馈)
 * + 假完成守护(write 任务校验工具证据)+ max-replan 兜底。mock ModelCallExecutor 控制 Plan 与
 * Execute 每步的 LLM 输出;ExplicitToolExecutioner 用真实实例 + fixture(对齐 ReActEngineTest 风格,
 * 更端到端:LLM 文本 → 真实解析 Action → 反射调 fixture.@Tool → 真实 Observation)。</p>
 */
class PlanExecuteEngineTest {

    @Test
    void declaresPlanExecuteParadigmAndName() {
        PlanExecuteEngine engine = newPlanExecuteEngine();
        assertThat(engine.paradigm()).isEqualTo(AgentParadigm.PLAN_EXECUTE);
        assertThat(engine.name()).isEqualTo("plan-execute-loop");
    }

    @Test
    void supportsWhenParadigmIsPlanExecute() {
        PlanExecuteEngine engine = newPlanExecuteEngine();
        assertThat(engine.supports(ctxWithParadigm(AgentParadigm.PLAN_EXECUTE))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        PlanExecuteEngine engine = newPlanExecuteEngine();
        assertThat(engine.supports(ctxWithParadigm(AgentParadigm.REACT))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newPlanExecuteEngine().supports(null)).isFalse();
    }

    // === PE-T3: Plan 阶段(BeanOutputConverter 结构化 List<PlanStep>)===

    /**
     * mock ModelCallExecutor.executeChat 返回结构化 Plan wrapper(2 步),
     * 调 runPlan 断言返回的 List<PlanStep> size=2、stepText 含 "搜索"/"综合"。
     * 验证 runPlan 正确解包 ModelCallResult<Plan>.value().steps()。
     */
    @Test
    void runPlanGeneratesStructuredPlanSteps() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        PlanExecuteEngine.Plan plan = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("搜索 X 相关资料"),
                new PlanExecuteEngine.PlanStep("综合搜索结果给出回答")
        ));
        AiProviderService.ActiveChatClient client = planExecuteClient();
        doReturn(new ModelCallExecutor.ModelCallResult<>(
                plan, client, List.of("test:test-model"), false)
        ).when(executor).executeChat(
                any(AiProviderService.ActiveChatClient.class),
                eq("plan-execute-plan"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any(ModelCallExecutor.ChatOperation.class));

        PlanExecuteEngine engine = newPlanExecuteEngineWith(executor);
        List<PlanExecuteEngine.PlanStep> steps = engine.runPlan(planExecuteCtx(client), client, new Object[0], "");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).stepText()).contains("搜索");
        assertThat(steps.get(1).stepText()).contains("综合");
    }

    /**
     * executeChat 抛异常时 runPlan 降级为空列表(不向调用方抛),为 PE-T4 重规划/兜底留出判断空间。
     */
    @Test
    void runPlanReturnsEmptyOnException() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        AiProviderService.ActiveChatClient client = planExecuteClient();
        org.mockito.Mockito.doThrow(new RuntimeException("model unavailable"))
                .when(executor).executeChat(
                        any(AiProviderService.ActiveChatClient.class),
                        eq("plan-execute-plan"),
                        any(ModelCallExecutor.ChatRequestContext.class),
                        eq(true),
                        any(ModelCallExecutor.ChatOperation.class));

        PlanExecuteEngine engine = newPlanExecuteEngineWith(executor);
        List<PlanExecuteEngine.PlanStep> steps = engine.runPlan(planExecuteCtx(client), client, null, "");

        assertThat(steps).isEmpty();
    }

    /**
     * I1 回归:runPlan 必须用传入的 {@code client} 形参,而非 {@code ctx.activeClient()}。
     * <p>场景:Execute 步触发 failover 后,runPlanExecute 把更新后的 activeClient 传给下一次 Replan 的
     * runPlan。若 runPlan 回退读 ctx.activeClient()(原始故障 provider),Replan 又会重新发现 failover。
     * 本测试用两个不同 client(ctx 携 A,传入 B),断言 executeChat 收到的是 B。</p>
     */
    @Test
    void runPlanUsesPassedClientNotCtxActiveClient() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        AiProviderService.ActiveChatClient ctxClient = planExecuteClient(); // provider="test"
        AiProviderService.ActiveChatClient failoverClient = new AiProviderService.ActiveChatClient(
                "failover", "failover-model", "http://localhost:2", null, true, null);
        when(executor.executeChat(
                any(AiProviderService.ActiveChatClient.class),
                eq("plan-execute-plan"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any(ModelCallExecutor.ChatOperation.class)))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        new PlanExecuteEngine.Plan(List.of()), failoverClient, List.of(), false));

        PlanExecuteEngine engine = newPlanExecuteEngineWith(executor);
        // ctx 携 ctxClient,但传 failoverClient 给 runPlan
        engine.runPlan(planExecuteCtx(ctxClient), failoverClient, new Object[0], "");

        ArgumentCaptor<AiProviderService.ActiveChatClient> captor =
                ArgumentCaptor.forClass(AiProviderService.ActiveChatClient.class);
        verify(executor).executeChat(captor.capture(),
                eq("plan-execute-plan"), any(), eq(true), any());
        assertThat(captor.getValue())
                .as("runPlan 应使用传入的 client(failover),而非 ctx.activeClient()(ctxClient)")
                .isEqualTo(failoverClient);
    }

    /**
     * 直连 BeanOutputConverter<Plan> 验证 record wrapper + List<PlanStep> 的结构化解析
     * (self-review 关注点:Java 泛型擦除下 wrapper record 是否真能 round-trip)。
     * 不依赖任何 mock——直接证明 format 非空、convert(JSON) 能还原 Plan.steps。
     */
    @Test
    void beanOutputConverterParsesPlanFromJson() {
        BeanOutputConverter<PlanExecuteEngine.Plan> converter =
                new BeanOutputConverter<>(PlanExecuteEngine.Plan.class);

        assertThat(converter.getFormat()).isNotBlank();

        PlanExecuteEngine.Plan plan = converter.convert(
                "{\"steps\":[{\"stepText\":\"搜索 X\"},{\"stepText\":\"综合结果\"}]}");

        assertThat(plan).isNotNull();
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).stepText()).isEqualTo("搜索 X");
        assertThat(plan.steps().get(1).stepText()).isEqualTo("综合结果");
    }

    // === PE-T4: Execute 逐步 + Replan + 假完成守护 ===

    /**
     * 主路径:Plan 产 2 步计划 → Execute 逐步(经真实 ExplicitToolExecutioner 手动执行工具)→ read 任务完成。
     * <p>断言要点:</p>
     * <ul>
     *   <li>search 工具被手动执行恰好 1 次(第1步 Action,第2步综合无 Action)——证明 Execute 真正手动调了 @Tool;</li>
     *   <li>循环跑满 plan 的 2 步(2 次 step LLM 调用)+ 1 次 plan LLM 调用 = 3 次;</li>
     *   <li>最终答案取末步(综合步)的 LLM 输出。</li>
     * </ul>
     */
    @Test
    void executeRunsEachPlanStepWithManualToolLoop() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        PlanExecuteToolFixture fixture = new PlanExecuteToolFixture();
        PlanExecuteEngine engine = newPlanExecuteEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2);

        AiProviderService.ActiveChatClient client = planExecuteClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // Plan:2 步计划
        PlanExecuteEngine.Plan plan = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("搜索 X 相关资料"),
                new PlanExecuteEngine.PlanStep("综合搜索结果给出回答")
        ));
        doReturn(planCallResult(plan, client))
                .when(executor).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());

        // Execute 两步的 LLM 输出
        String step1Thought = "Thought: 需要搜索 X\nAction: search(query=\"X\")";
        String step2Thought = "最终答案: X 是一个 AI 框架(已综合)。";
        doReturn(textCallResult(step1Thought, client), textCallResult(step2Thought, client))
                .when(executor).executeChat(any(), startsWith("plan-execute-step"), any(), anyBoolean(), any());

        ChatExecutionResult result = engine.execute(planExecuteCtx(client), (reason, ctx) -> "fallback");

        // 工具被手动执行(Execute 主路径核心证据)
        assertThat(fixture.searchCalls.get())
                .as("Execute 应手动执行 search 工具恰好 1 次(第1步 Action,第2步综合无 Action)")
                .isEqualTo(1);
        // 循环行为:plan(1) + 2 step = 3 次 LLM 调用
        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.reflect()).contains("X 是一个 AI 框架"); // 末步综合答案
        assertThat(result.plan()).contains("Plan 2 步").contains("Execute 2 步");
        verify(executor, times(3)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    // === PE-T5: ChatExecutionResult 五字段投影 + resolveFinalAnswer ===

    /**
     * PE-T5 结果投影:驱动完整 Plan + Execute(2 步计划,第1步调工具,第2步综合),
     * 断言 {@link ChatExecutionResult} 五字段完整投影——
     * <ul>
     *   <li>plan = "Plan-Execute: Plan 2 步,Execute 2 步"(Plan 步数 + Execute 步数双计数)</li>
     *   <li>action = Plan 轨迹(两步 stepText)+ Execute 轨迹(每步 stepText + Thought + Observation)</li>
     *   <li>reflect = 末步最终答案 + "步骤概要:\n" + buildExecutedHistory(每步 stepText/Thought/Observation)</li>
     *   <li>modelEnabled = true</li>
     * </ul>
     * <p>对齐 ReActEngineTest 的结果断言风格,聚焦结果结构而非循环行为(循环由 PE-T4 测试覆盖)。</p>
     */
    @Test
    void buildsChatExecutionResultWithPlanExecuteTrace() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        PlanExecuteToolFixture fixture = new PlanExecuteToolFixture();
        PlanExecuteEngine engine = newPlanExecuteEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2);

        AiProviderService.ActiveChatClient client = planExecuteClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // Plan:2 步计划
        PlanExecuteEngine.Plan plan = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("搜索 X 相关资料"),
                new PlanExecuteEngine.PlanStep("综合搜索结果给出回答")
        ));
        doReturn(planCallResult(plan, client))
                .when(executor).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());

        // Execute:第1步调 search 工具,第2步综合(无 Action)
        String step1Thought = "Thought: 需要搜索 X\nAction: search(query=\"X\")";
        String step2Thought = "最终答案: X 是一个 AI 框架(已综合)。";
        doReturn(textCallResult(step1Thought, client), textCallResult(step2Thought, client))
                .when(executor).executeChat(any(), startsWith("plan-execute-step"), any(), anyBoolean(), any());

        ChatExecutionResult result = engine.execute(planExecuteCtx(client), (reason, ctx) -> "fallback");

        // plan 字段:Plan 步数 + Execute 步数双计数(透明反映 Plan/Execute 两阶段)
        assertThat(result.plan()).isEqualTo("Plan-Execute: Plan 2 步,Execute 2 步");

        // action 字段:Plan 轨迹([Plan] N. stepText)+ Execute 轨迹([Step N] stepText + Thought + Observation)
        assertThat(result.action())
                .contains("[Plan] 1. 搜索 X 相关资料")
                .contains("[Plan] 2. 综合搜索结果给出回答")
                .contains("[Step 1] 搜索 X 相关资料")
                .contains("Thought: 需要搜索 X")
                .contains("Observation: 搜索结果:X") // 第1步工具真实 observation
                .contains("[Step 2] 综合搜索结果给出回答")
                .contains("X 是一个 AI 框架"); // 第2步 Thought(综合答案)

        // reflect 字段:末步最终答案 + 步骤概要(每步 stepText/Thought/Observation)
        assertThat(result.reflect())
                .contains("X 是一个 AI 框架") // 末步答案
                .contains("步骤概要")
                .contains("搜索 X 相关资料") // 步骤概要含 plan stepText
                .contains("Observation: 搜索结果:X"); // 步骤概要含工具 observation

        assertThat(result.modelEnabled()).isTrue();
    }

    /**
     * M1:首次 Plan 返回空计划时,终态 reflect 是 "Plan 阶段未生成有效步骤",
     * <b>不是</b> max-replan 兜底消息 "已达 max-replan"——两路径消息区分,避免混淆。
     * <p>且循环在首次空计划即返回(1 次 plan 调用,0 次 step 调用,不进入 Execute)。</p>
     */
    @Test
    void emptyPlanDistinguishedFromMaxReplanFallback() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        PlanExecuteEngine engine = newPlanExecuteEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2);

        AiProviderService.ActiveChatClient client = planExecuteClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // Plan 始终返回空计划
        doReturn(planCallResult(new PlanExecuteEngine.Plan(List.of()), client))
                .when(executor).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());

        ChatExecutionResult result = engine.execute(planExecuteCtx(client), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        // M1:空计划消息,不是 max-replan 兜底
        assertThat(result.reflect()).startsWith("Plan 阶段未生成有效步骤");
        assertThat(result.reflect()).doesNotContain("已达 max-replan");
        // 首次空计划即终止:1 次 plan 调用,未进入 Execute(0 次 step)
        verify(executor, times(1)).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());
        verify(executor, times(0)).executeChat(any(), startsWith("plan-execute-step"), any(), anyBoolean(), any());
    }

    /**
     * Replan 路径:第1次 plan 的步骤工具失败(未找到工具)→ 带 feedback Replan → 新 plan 的步骤成功 → 完成。
     * <p>断言:plan 调用 2 次(首规划 + 1 次 Replan)、step 调用 2 次(失败步 + 成功步)共 4 次 LLM 调用;
     * 最终答案取第2次 plan 成功步的输出。</p>
     */
    @Test
    void replanOnStepFailureAndRetry() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        // tools 为空 → ExplicitToolExecutioner 找不到 unknownTool → "(未找到工具: unknownTool)" → stepFailed
        PlanExecuteEngine engine = newPlanExecuteEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2);

        AiProviderService.ActiveChatClient client = planExecuteClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // Plan:第1次返回含未知工具步骤,第2次 Replan 返回综合步骤
        PlanExecuteEngine.Plan plan1 = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("调用 unknownTool 获取数据")));
        PlanExecuteEngine.Plan plan2 = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("用本地数据综合回答")));
        doReturn(planCallResult(plan1, client), planCallResult(plan2, client))
                .when(executor).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());

        // Execute:第1次失败(Action 调未知工具),第2次成功(纯答案)
        String failThought = "Thought: 调用工具\nAction: unknownTool(query=\"X\")";
        String successThought = "最终答案: 已用本地数据综合完成。";
        doReturn(textCallResult(failThought, client), textCallResult(successThought, client))
                .when(executor).executeChat(any(), startsWith("plan-execute-step"), any(), anyBoolean(), any());

        ChatExecutionResult result = engine.execute(planExecuteCtx(client), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.reflect()).contains("已用本地数据综合完成"); // 第2次 plan 成功步答案
        // 2 plan + 2 step = 4 次 LLM 调用
        verify(executor, times(4)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    /**
     * 假完成守护:write 任务,LLM 每步直接给"最终答案"文本(无 Action 行)→ tracker 无写证据 →
     * satisfiesCompletionCondition("write")=false → 拒绝完成 → Replan。max-replan 触达后返回兜底结果。
     * <p>断言:循环未在第1次 plan 完成,跑到 max-replan=1(2 次 plan + 2 次 step = 4 次 LLM 调用);
     * reflect 主答案是 max-replan 兜底提示(非 LLM 的假答案)。</p>
     */
    @Test
    void writeTaskFakeCompletionGuardRejects() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        PlanExecuteEngine engine = newPlanExecuteEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 1); // maxReplan=1

        AiProviderService.ActiveChatClient client = planExecuteClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // Plan:始终返回 1 步计划
        PlanExecuteEngine.Plan plan = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("创建文件 hello.txt")));
        doReturn(planCallResult(plan, client))
                .when(executor).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());

        // Execute:每步给"最终答案"文本(无 Action 行)→ tracker 无写证据 → 假完成拦截
        String fakeFinal = "最终答案: 我已经创建了文件 hello.txt。";
        doReturn(textCallResult(fakeFinal, client))
                .when(executor).executeChat(any(), startsWith("plan-execute-step"), any(), anyBoolean(), any());

        AgentDecision writeDecision = new AgentDecision(
                "general", "basic_model", List.of(), "write", false, "test write task");
        ChatExecutionResult result = engine.execute(
                planExecuteCtx(client, writeDecision), (reason, ctx) -> "fallback");

        // 假完成被拦截:2 次 plan + 2 次 step = 4 次 LLM 调用(跑到 max-replan=1)
        verify(executor, times(4)).executeChat(any(), anyString(), any(), anyBoolean(), any());
        assertThat(result.modelEnabled()).isTrue();
        // reflect 主答案是 max-replan 兜底提示(非假答案)——假完成守护仍生效
        assertThat(result.reflect()).startsWith("已达 max-replan");
    }

    /**
     * 反向校验:read 任务(riskLevel=read),计划执行完即完成,不校验工具证据 → 不会被假完成守护拦下。
     */
    @Test
    void readTaskCompletesWithoutToolEvidence() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        PlanExecuteEngine engine = newPlanExecuteEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2);

        AiProviderService.ActiveChatClient client = planExecuteClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        PlanExecuteEngine.Plan plan = new PlanExecuteEngine.Plan(List.of(
                new PlanExecuteEngine.PlanStep("直接分析给出结论")));
        doReturn(planCallResult(plan, client))
                .when(executor).executeChat(any(), eq("plan-execute-plan"), any(), anyBoolean(), any());

        String finalAnswer = "最终答案: 这是一个只读分析结论。";
        doReturn(textCallResult(finalAnswer, client))
                .when(executor).executeChat(any(), startsWith("plan-execute-step"), any(), anyBoolean(), any());

        // decision=null → riskLevel=read
        ChatExecutionResult result = engine.execute(planExecuteCtx(client), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.reflect()).contains("只读分析结论"); // 真答案透传
        // plan(1) + step(1) = 2 次 LLM 调用,第1次 plan 即完成(无 Replan)
        verify(executor, times(2)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    /**
     * I2 回归:stepFailed 必须要求错误说明以 "(" 起首才判定工具失败。
     * <p>ExplicitToolExecutioner 的错误 observation 都以 "(" 起首(如 "(未找到工具: X)");
     * 合法工具 observation 可能含"未找到"/"失败"关键词(如 search 返回"未找到相关文档"、grep 返回"0 失败"),
     * 但不以 "(" 起首——加 startsWith("(") 前缀避免这类合法 observation 误判步骤失败、触发无谓 Replan。</p>
     */
    @Test
    void stepFailedRequiresParenPrefixForErrorObservation() {
        PlanExecuteEngine engine = newPlanExecuteEngine();
        // 合法 observation 含关键词但不以 "(" 起首 → 不判失败
        assertThat(engine.stepFailed("搜索完成:未找到相关文档", "Thought: 已搜索", true)).isFalse();
        assertThat(engine.stepFailed("grep 结果: 0 失败行", "Thought: 已 grep", true)).isFalse();
        assertThat(engine.stepFailed("解析完成,无无法解析的残留", "Thought: 已解析", true)).isFalse();
        // 错误说明以 "(" 起首 + 含关键词 → 判失败
        assertThat(engine.stepFailed("(未找到工具: foo)", "Thought: 调工具", true)).isTrue();
        assertThat(engine.stepFailed("(工具执行失败: NPE)", "Thought: 调工具", true)).isTrue();
        assertThat(engine.stepFailed("(Action 格式无法解析: ...)", "Thought: 调工具", true)).isTrue();
        // 以 "(" 起首但不含失败关键词(如 "(工具返回 null)")→ 不判失败
        assertThat(engine.stepFailed("(工具返回 null)", "Thought: 调工具", true)).isFalse();
    }

    /**
     * 测试用工具 fixture:反射扫 {@link Tool} 的目标(模拟真实 tool pack bean)。
     * searchCalls 计数器用于断言 Execute 主路径**手动执行**了工具(经 ExplicitToolExecutioner 反射调用)。
     */
    static class PlanExecuteToolFixture {
        final AtomicInteger searchCalls = new AtomicInteger();

        @Tool(name = "search", description = "搜索知识库")
        public String search(String query) {
            searchCalls.incrementAndGet();
            return "搜索结果:" + query;
        }
    }

    /**
     * 构造 PlanExecuteEngine,注入指定的 ModelCallExecutor mock(其余 10 bean 仍 mock)。
     * PE-T4 起构造函数新增 ExplicitToolExecutioner(真实实例)。
     */
    private PlanExecuteEngine newPlanExecuteEngineWith(ModelCallExecutor executor) {
        return new PlanExecuteEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(ModelTransportGuardService.class),
                executor,
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(ChatGuardService.class),
                mock(RunLifecycleObserver.class),
                new ExplicitToolExecutioner(),
                2
        );
    }

    /**
     * PE-T4 循环测试用:注入可 stub 的 ModelCallExecutor / ToolOrchestrator / Guard +
     * ExplicitToolExecutioner(真实)+ 自定义 maxReplan。
     */
    private PlanExecuteEngine newPlanExecuteEngine(ModelCallExecutor executor,
                                                   ToolOrchestrator toolOrchestrator,
                                                   ModelTransportGuardService guard,
                                                   ExplicitToolExecutioner toolExecutioner,
                                                   int maxReplan) {
        return new PlanExecuteEngine(
                mock(AiProviderService.class),
                toolOrchestrator,
                guard,
                executor,
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(ChatGuardService.class),
                mock(RunLifecycleObserver.class),
                toolExecutioner,
                maxReplan
        );
    }

    /**
     * 构造 PlanExecuteEngine 骨架:mock 11 bean 依赖 + 真实 ExplicitToolExecutioner + maxReplan=2。
     * 依赖签名对齐 ReActEngine(PE-T4 接入 ExplicitToolExecutioner)。
     */
    private PlanExecuteEngine newPlanExecuteEngine() {
        return new PlanExecuteEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(ChatGuardService.class),
                mock(RunLifecycleObserver.class),
                new ExplicitToolExecutioner(),
                2
        );
    }

    /**
     * 构造可用的 ActiveChatClient(真实 record,无需 mock)。chatClient 置 null——
     * ModelCallExecutor 被 mock,真实调用 lambda 不会执行,无需 ChatClient。
     */
    private AiProviderService.ActiveChatClient planExecuteClient() {
        return new AiProviderService.ActiveChatClient(
                "test", "test-model", "http://localhost", null, true, null);
    }

    /**
     * 构造 ModelCallResult<Plan>(value=结构化 Plan,client=failover 后客户端)。
     */
    private ModelCallExecutor.ModelCallResult<PlanExecuteEngine.Plan> planCallResult(
            PlanExecuteEngine.Plan plan, AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(plan, client, List.of(), false);
    }

    /**
     * 构造 ModelCallResult<String>(value=Execute 每步的 LLM 文本输出,client=failover 后客户端)。
     */
    private ModelCallExecutor.ModelCallResult<String> textCallResult(
            String text, AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(text, client, List.of(), false);
    }

    /**
     * 构造 runPlanExecute 所需的最小 ChatContext——带 assembled(含 question)、channel/userId/requestId、
     * decision=null(runPlanExecute 兜底 riskLevel=read)、paradigm=PLAN_EXECUTE。
     */
    private ChatContext planExecuteCtx(AiProviderService.ActiveChatClient client) {
        return planExecuteCtx(client, null);
    }

    /**
     * 带 AgentDecision 的 planExecuteCtx 重载:PE-T4 假完成测试用它注入 riskLevel=write 等。
     */
    private ChatContext planExecuteCtx(AiProviderService.ActiveChatClient client, AgentDecision decision) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test-channel", "user-1",
                "请搜索 X 并综合给出回答",
                "", "", "# 当前问题\n请搜索 X 并综合给出回答");
        return new ChatContext(
                null, "test-channel", "user-1", null,
                "请搜索 X 并综合给出回答", "请搜索 X 并综合给出回答",
                "req-1", "SOUL", assembled, client,
                "plan-execute", null, "agent", "general",
                decision, null, null, AgentParadigm.PLAN_EXECUTE
        );
    }

    /**
     * 构造带 paradigm 的 ChatContext(canonical 18 参构造)。
     * supports() 只读 paradigm,其余字段置 null 即可。
     */
    private ChatContext ctxWithParadigm(AgentParadigm paradigm) {
        return new ChatContext(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, paradigm
        );
    }
}
