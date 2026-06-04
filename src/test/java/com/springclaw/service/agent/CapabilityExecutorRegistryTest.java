package com.springclaw.service.agent;

import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityExecutorRegistryTest {

    @Test
    void shouldPlanAndExecuteMatchingPluggableExecutorsOnly() {
        CapabilityExecutor workspace = new FakeExecutor("workspace", "workspace_analysis");
        CapabilityExecutor web = new FakeExecutor("web", "web_research");
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(List.of(web, workspace));
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search", "workspace-review"),
                "read",
                false,
                "项目分析"
        );

        CapabilityPlan plan = registry.plan(decision);
        List<CapabilityResult> results = registry.execute(
                decision,
                new AssembledContext("s1", "api", "u1", "分析当前项目结构", "", "", ""),
                "req-1"
        );

        assertThat(plan.toolsets()).containsExactly("workspace");
        assertThat(results).extracting(CapabilityResult::toolset).containsExactly("workspace");
        assertThat(results.get(0).payload()).contains("workspace executed");
    }

    @Test
    void shouldSkipDangerousOrConfirmationDecisions() {
        CapabilityExecutor workspace = new FakeExecutor("workspace", "workspace_analysis");
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(List.of(workspace));
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search"),
                "write",
                true,
                "需要确认"
        );

        List<CapabilityResult> results = registry.execute(
                decision,
                new AssembledContext("s1", "api", "u1", "写入文件", "", "", ""),
                "req-1"
        );

        assertThat(results).isEmpty();
    }

    private record FakeExecutor(String toolset, String intent) implements CapabilityExecutor {
        @Override
        public boolean supports(AgentDecision decision) {
            return decision != null && intent.equalsIgnoreCase(decision.intent());
        }

        @Override
        public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
            return List.of(new CapabilityResult(toolset + ".run", toolset, "success", "fake", toolset + " executed", 12L, "read"));
        }
    }
}
