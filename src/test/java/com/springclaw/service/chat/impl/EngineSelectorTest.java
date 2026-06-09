package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.CapabilityExecutorRegistry;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
