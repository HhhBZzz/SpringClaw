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
 * MultiAgentEngine 单测(MA-T1 骨架)——验证 paradigm/name/supports 接入地基。
 * mock 模式对齐 {@link ReflexionEngineTest}(11 bean + 真实 {@link ExplicitToolExecutioner})。
 * 循环体(decompose/aggregate/runMultiAgentLoop)由 MA-T2/T3 填充,本任务只验骨架元数据。
 */
class MultiAgentEngineTest {

    @Test
    void declaresMultiAgentParadigmAndName() {
        assertThat(newMultiAgentEngine().paradigm()).isEqualTo(AgentParadigm.MULTI_AGENT);
        assertThat(newMultiAgentEngine().name()).isEqualTo("multi-agent-loop");
    }

    @Test
    void supportsWhenParadigmIsMultiAgent() {
        assertThat(newMultiAgentEngine().supports(ctxWithParadigm(AgentParadigm.MULTI_AGENT))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        assertThat(newMultiAgentEngine().supports(ctxWithParadigm(AgentParadigm.REFLECTION))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newMultiAgentEngine().supports(null)).isFalse();
    }

    /**
     * 构造 MultiAgentEngine 骨架:mock 11 bean 依赖 + 真实 ExplicitToolExecutioner + maxAgents=5。
     * 依赖签名对齐 {@link ReflexionEngine}(MA-T1 接入 ExplicitToolExecutioner)。
     */
    private MultiAgentEngine newMultiAgentEngine() {
        return new MultiAgentEngine(
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
                5
        );
    }

    /**
     * 构造带 paradigm 的 ChatContext(canonical 18 参构造)。
     * supports() 只读 paradigm,其余字段置 null 即可。仿 {@link ReflexionEngineTest#ctxWithParadigm}。
     */
    private ChatContext ctxWithParadigm(AgentParadigm paradigm) {
        return new ChatContext(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, paradigm
        );
    }
}
