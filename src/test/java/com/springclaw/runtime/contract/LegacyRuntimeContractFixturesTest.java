package com.springclaw.runtime.contract;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves current production records ({@link AssembledContext}, {@link AgentDecision})
 * can populate the new unified-runtime contracts through a test-only translator.
 *
 * <p>This is NOT a production adapter — it lives under {@code src/test} and exists
 * only to demonstrate compatibility before real adapters are introduced in a later
 * migration phase. See unified-runtime-domain-contracts plan Task 8.
 */
class LegacyRuntimeContractFixturesTest {

    private static final Instant CAPTURED = Instant.parse("2026-06-19T00:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-06-19T00:00:01Z");

    @Test
    void contextSnapshotTranslatesFromAssembledContext() {
        AssembledContext assembled = new AssembledContext(
                "session-1", "web", "user-1", "为什么登录失败",
                "[event] hello", "[semantic] previous error", "observePrompt",
                1, 0);

        ContextSnapshot snapshot = LegacyRuntimeContractFixtures.contextSnapshot(
                "run-1", assembled, "USER", CAPTURED);

        assertThat(snapshot.runId()).isEqualTo("run-1");
        assertThat(snapshot.sessionKey()).isEqualTo(assembled.sessionKey());
        assertThat(snapshot.effectiveMessage()).isEqualTo(assembled.question());
        assertThat(snapshot.shortTermEvents()).containsExactly(assembled.eventContext());
        assertThat(snapshot.semanticRecallItems()).containsExactly(assembled.semanticContext());
        assertThat(snapshot.snapshotHash()).isNotBlank();
        assertThat(snapshot.capturedAt()).isEqualTo(CAPTURED);
    }

    @Test
    void executionDecisionTranslatesFromAgentDecision() {
        AgentDecision legacy = new AgentDecision(
                "workspace_analysis", "agent_tools",
                List.of("workspace.edit", "workspace.read"),
                "write", true, "matched workspace capabilities");

        ExecutionDecision decision = LegacyRuntimeContractFixtures.executionDecision(
                "run-1", legacy, "agent", DECIDED);

        assertThat(decision.runId()).isEqualTo("run-1");
        assertThat(decision.intent()).isEqualTo(legacy.intent());
        assertThat(decision.selectedCapabilityIds())
                .containsExactlyElementsOf(legacy.selectedCapabilities());
        assertThat(decision.riskSummary()).isEqualTo(legacy.riskLevel());
        assertThat(decision.responseMode()).isEqualTo("agent");
        assertThat(decision.decidedAt()).isEqualTo(DECIDED);
        assertThat(decision.decisionSource()).isNotBlank();
    }
}
