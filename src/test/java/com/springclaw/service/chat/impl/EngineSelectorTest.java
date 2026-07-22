package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.CapabilityExecutorRegistry;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EngineSelectorTest {

    @Test
    void shouldPreferOparEngineWhenExecutionModeIsOpar() {
        EngineSelector selector = new EngineSelector(List.of(runtimeEngine(), oparLoopEngine()));
        AgentDecision decision = workspaceDecision();

        AgentEngine selected = selector.select(context("opar", "用户显式选择 OPAR", decision));

        assertThat(selected.name()).isEqualTo("opar-loop");
    }

    @Test
    void shouldPreferOparEngineWhenRoutingPolicyAutoUpgrades() {
        EngineSelector selector = new EngineSelector(List.of(runtimeEngine(), oparLoopEngine()));
        AgentDecision decision = workspaceDecision();

        AgentEngine selected = selector.select(context("simplified", "复杂任务自动升级到 OPAR", decision));

        assertThat(selected.name()).isEqualTo("opar-loop");
    }

    @Test
    void runtimeAndOparEnginesDeclareOparParadigm() {
        assertThat(runtimeEngine().paradigm()).isEqualTo(AgentParadigm.OPAR);
        assertThat(oparLoopEngine().paradigm()).isEqualTo(AgentParadigm.OPAR);
    }

    @Test
    void shouldFailInitializationWhenEngineNameHasNoLegacyRank() {
        AgentEngine unknown = new AgentEngine() {
            @Override
            public String name() {
                return "brand-new-engine";
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public boolean supports(ChatContext ctx) {
                return false;
            }

            @Override
            public com.springclaw.service.chat.impl.ChatExecutionResult execute(
                    ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AgentParadigm paradigm() {
                return AgentParadigm.SINGLE_TURN;
            }
        };

        assertThatThrownBy(() -> new EngineSelector(List.of(unknown)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("brand-new-engine");
    }

    @Test
    void selectByParadigmReturnsFirstSupportingEngineOfThatParadigm() {
        AgentEngine singleTurnA = stub("simplified", 60, AgentParadigm.SINGLE_TURN, true);
        AgentEngine singleTurnB = stub("basic-stream", 10, AgentParadigm.SINGLE_TURN, true);
        AgentEngine opar = stub("opar-loop", 40, AgentParadigm.OPAR, true);
        EngineSelector selector = new EngineSelector(List.of(singleTurnA, singleTurnB, opar));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        AgentEngine selected = selector.select(ctx, AgentParadigm.SINGLE_TURN);

        // 同 SINGLE_TURN 范式里按 priority+legacyRank,basic-stream(p=10) 先于 simplified(p=60)
        assertThat(selected.name()).isEqualTo("basic-stream");
    }

    @Test
    void selectByParadigmThrowsWhenNoEngineOfThatParadigmSupportsContext() {
        AgentEngine singleTurnUnsupported = stub("simplified", 60, AgentParadigm.SINGLE_TURN, false);
        EngineSelector selector = new EngineSelector(List.of(singleTurnUnsupported));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        assertThatThrownBy(() -> selector.select(ctx, AgentParadigm.SINGLE_TURN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SINGLE_TURN");
    }

    @Test
    void selectByPlaceholderParadigmThrowsNotImplemented() {
        AgentEngine singleTurn = stub("simplified", 60, AgentParadigm.SINGLE_TURN, true);
        EngineSelector selector = new EngineSelector(List.of(singleTurn));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        assertThatThrownBy(() -> selector.select(ctx, AgentParadigm.REACT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未实现");
    }

    @Test
    void selectByNullParadigmDelegatesToDefaultRouting() {
        AgentEngine singleTurn = stub("simplified", 60, AgentParadigm.SINGLE_TURN, true);
        AgentEngine opar = stub("opar-loop", 40, AgentParadigm.OPAR, true);
        EngineSelector selector = new EngineSelector(List.of(singleTurn, opar));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        AgentEngine selected = selector.select(ctx, null);

        // null paradigm 走原逻辑:按 priority+legacyRank 选第一个 supports()(opar-loop p=40 先于 simplified p=60)
        assertThat(selected.name()).isEqualTo("opar-loop");
    }

    /** 构造一个可自由指定 name/priority/paradigm/supports 的 stub 引擎,用于选择逻辑单测。 */
    private AgentEngine stub(String name, int priority, AgentParadigm paradigm, boolean supports) {
        return new AgentEngine() {
            @Override public String name() { return name; }
            @Override public AgentParadigm paradigm() { return paradigm; }
            @Override public int priority() { return priority; }
            @Override public boolean supports(ChatContext ctx) { return supports; }
            @Override
            public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AgentRuntimeEngine runtimeEngine() {
        return new AgentRuntimeEngine(
                mock(CapabilityExecutorRegistry.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                mock(ChatResponsePolicyService.class)
        );
    }

    private OparLoopEngine oparLoopEngine() {
        return new OparLoopEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(LocalSkillFallbackService.class),
                mock(LocalExecutionNarrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(OparContextAwareSupport.class),
                mock(OparPromptSupport.class),
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                true,
                true,
                3
        );
    }

    private AgentDecision workspaceDecision() {
        return new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-review"),
                "read",
                false,
                "项目分析"
        );
    }

    private ChatContext context(String executionMode, String routingReason, AgentDecision decision) {
        return new ChatContext(
                null,
                "api",
                "u1",
                "USER",
                "分析当前项目结构",
                "分析当前项目结构",
                "req-test",
                "system",
                null,
                null,
                executionMode,
                routingReason,
                "deep",
                decision.intent(),
                decision
        );
    }
}
