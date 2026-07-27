package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
