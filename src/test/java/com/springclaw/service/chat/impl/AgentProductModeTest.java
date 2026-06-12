package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentProductModeTest {

    @Test
    void shouldClassifyGeneralFastPathAsQuickAnswer() {
        AgentDecision decision = AgentDecision.general("普通聊天");

        String productMode = AgentProductMode.resolve("fast", "simplified", "general", decision);

        Assertions.assertEquals("quick_answer", productMode);
    }

    @Test
    void shouldClassifyReadOnlyAgentDecisionAsAgentAnalysis() {
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                java.util.List.of("workspace-review"),
                "read",
                false,
                "项目分析"
        );

        String productMode = AgentProductMode.resolve("agent", "simplified", "workspace_analysis", decision);

        Assertions.assertEquals("agent_analysis", productMode);
    }

    @Test
    void shouldClassifyWriteConfirmationDecisionAsExecutionTask() {
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                java.util.List.of("workspace-edit"),
                "write",
                true,
                "写入需要确认"
        );

        String productMode = AgentProductMode.resolve("agent", "opar", "workspace_analysis", decision);

        Assertions.assertEquals("execution_task", productMode);
    }

    @Test
    void shouldClassifyDangerousRiskAsExecutionTaskEvenWithoutConfirmationFlag() {
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "basic_model",
                java.util.List.of(),
                "dangerous",
                false,
                "危险操作"
        );

        String productMode = AgentProductMode.resolve("agent", "simplified", "workspace_analysis", decision);

        Assertions.assertEquals("execution_task", productMode);
    }
}
