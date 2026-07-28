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
 * ReflexionEngine 单测(RX-T1 骨架)——验证 paradigm/name/supports 接入地基。
 * <p>循环体(Actor→Evaluator→Reflector,RX-T3)留待后续填充,此处只测骨架元数据 + 路由判定。
 * mock 模式对齐 {@link PlanExecuteEngineTest}(11 bean + 真实 {@link ExplicitToolExecutioner})。</p>
 */
class ReflexionEngineTest {

    @Test
    void declaresReflexionParadigmAndName() {
        assertThat(newReflexionEngine().paradigm()).isEqualTo(AgentParadigm.REFLECTION);
        assertThat(newReflexionEngine().name()).isEqualTo("reflexion-loop");
    }

    @Test
    void supportsWhenParadigmIsReflection() {
        assertThat(newReflexionEngine().supports(ctxWithParadigm(AgentParadigm.REFLECTION))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        assertThat(newReflexionEngine().supports(ctxWithParadigm(AgentParadigm.REACT))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newReflexionEngine().supports(null)).isFalse();
    }

    /**
     * 构造 ReflexionEngine 骨架:mock 11 bean 依赖 + 真实 ExplicitToolExecutioner + maxReflections=3。
     * 依赖签名对齐 {@link PlanExecuteEngine}(RX-T1 接入 ExplicitToolExecutioner)。
     */
    private ReflexionEngine newReflexionEngine() {
        return new ReflexionEngine(
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
                3
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
