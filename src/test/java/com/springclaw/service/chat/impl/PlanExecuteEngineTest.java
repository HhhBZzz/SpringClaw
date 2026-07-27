package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * PlanExecuteEngine 骨架单测(PE-T2):只锁定接入地基不变量——
 * 声明 PLAN_EXECUTE 范式、name() 登记 "plan-execute-loop"、supports() 仅在 paradigm=PLAN_EXECUTE 时为真。
 * Plan/Execute 循环体留待 Task 3/4 填充,故此处不驱动 execute/stream。
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
        List<PlanExecuteEngine.PlanStep> steps = engine.runPlan(planExecuteCtx(client), new Object[0], "");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).stepText()).contains("搜索");
        assertThat(steps.get(1).stepText()).contains("综合");
    }

    /**
     * executeChat 抛异常时 runPlan 降级为空列表(不向调用方抛),为 Task 4 重规划/兜底留出判断空间。
     */
    @Test
    void runPlanReturnsEmptyOnException() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        AiProviderService.ActiveChatClient client = planExecuteClient();
        doThrow(new RuntimeException("model unavailable"))
                .when(executor).executeChat(
                        any(AiProviderService.ActiveChatClient.class),
                        eq("plan-execute-plan"),
                        any(ModelCallExecutor.ChatRequestContext.class),
                        eq(true),
                        any(ModelCallExecutor.ChatOperation.class));

        PlanExecuteEngine engine = newPlanExecuteEngineWith(executor);
        List<PlanExecuteEngine.PlanStep> steps = engine.runPlan(planExecuteCtx(client), null, "");

        assertThat(steps).isEmpty();
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

    /**
     * 构造 PlanExecuteEngine,注入指定的 ModelCallExecutor mock(其余 10 bean 仍 mock)。
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
                2
        );
    }

    /**
     * 构造 PlanExecuteEngine 骨架:mock 11 bean 依赖 + maxReplan=2。
     * 依赖签名对齐 ReActEngine(剔除 Task 4 才接入的 ExplicitToolExecutioner)。
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
                2
        );
    }

    /**
     * 构造测试用 ActiveChatClient(真实 record,无需 mock)。
     */
    private AiProviderService.ActiveChatClient planExecuteClient() {
        return new AiProviderService.ActiveChatClient(
                "test", "test-model", "http://localhost", null, true, null);
    }

    /**
     * 构造 runPlan 所需的最小 ChatContext——带 assembled(含 question)、channel/userId/requestId。
     * executeChat 被 mock,故 lambda 不实际执行;renderPlanPrompt 会读取 question/injection。
     */
    private ChatContext planExecuteCtx(AiProviderService.ActiveChatClient client) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test-channel", "user-1",
                "请搜索 X 并综合给出回答",
                "", "", "# 当前问题\n请搜索 X 并综合给出回答");
        return new ChatContext(
                null, "test-channel", "user-1", null,
                "请搜索 X 并综合给出回答", "请搜索 X 并综合给出回答",
                "req-1", "SOUL", assembled, client,
                "plan-execute", null, "agent", "general",
                null, null, null, AgentParadigm.PLAN_EXECUTE
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
