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
 * ReActEngine 骨架单测(Task 1):只锁定接入地基不变量——
 * 声明 REACT 范式、name() 登记 "react-loop"、supports() 仅在 paradigm=REACT 时为真。
 * 循环体(Thought-Action-Observation)留待 Task 3 填充,故此处不驱动 execute/stream。
 */
class ReActEngineTest {

    @Test
    void declaresReactParadigmAndSelectorName() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.paradigm()).isEqualTo(AgentParadigm.REACT);
        assertThat(engine.name()).isEqualTo("react-loop");
    }

    @Test
    void supportsWhenParadigmIsReact() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.REACT))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.OPAR))).isFalse();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.AUTONOMOUS_LOOP))).isFalse();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.SINGLE_TURN))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newReActEngine().supports(null)).isFalse();
    }

    /**
     * 构造 ReActEngine 骨架:mock 11 bean 依赖 + maxReactSteps=6。
     * 依赖签名与 AutonomousLoopEngine 一致(见该类构造函数)。
     */
    private ReActEngine newReActEngine() {
        return new ReActEngine(
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
                6
        );
    }

    /**
     * 构造带 paradigm 的 ChatContext(canonical 18 参构造)。
     * supports() 只读 paradigm,其余字段置 null 即可。
     */
    private ChatContext contextWithParadigm(AgentParadigm paradigm) {
        return new ChatContext(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, paradigm
        );
    }
}
