package com.springclaw.runtime.contract;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                "session-1", "feishu", "user-1", "为什么登录失败",
                "[event] hello", "[semantic] previous error",
                """
                # 当前问题
                为什么登录失败

                # 项目记忆（Memory Bank）
                - use the unified runtime
                - preserve evidence

                # 短期会话上下文（事件流）
                [event] hello

                # 长期语义记忆（同会话优先）
                [semantic] previous error
                """,
                2, 1);

        ContextSnapshot snapshot = LegacyRuntimeContractFixtures.contextSnapshot(
                "run-1", assembled, "ADMIN", CAPTURED);

        assertThat(snapshot.runId()).isEqualTo("run-1");
        assertThat(snapshot.sessionKey()).isEqualTo(assembled.sessionKey());
        assertThat(snapshot.channel()).isEqualTo("feishu");
        assertThat(snapshot.userId()).isEqualTo("user-1");
        assertThat(snapshot.roleCode()).isEqualTo("ADMIN");
        assertThat(snapshot.originalMessage()).isEqualTo("为什么登录失败");
        assertThat(snapshot.effectiveMessage()).isEqualTo(assembled.question());
        assertThat(snapshot.memoryBankText())
                .isEqualTo("- use the unified runtime\n- preserve evidence");
        assertThat(snapshot.shortTermEvents()).containsExactly(assembled.eventContext());
        assertThat(snapshot.semanticRecallItems()).containsExactly(assembled.semanticContext());
        assertThat(snapshot.allowedCapabilities()).isEmpty();
        assertThat(snapshot.contextSourceSummary()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "schema", "springclaw.context-source.v1",
                "learningActive", "2",
                "learningFiltered", "1"
        ));
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
        assertThat(decision.goal()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilityIds())
                .containsExactlyElementsOf(legacy.selectedCapabilities());
        assertThat(decision.riskSummary()).isEqualTo(legacy.riskLevel());
        assertThat(decision.responseMode()).isEqualTo("agent");
        assertThat(decision.confidence()).isEqualTo(1.0);
        assertThat(decision.reason()).isEqualTo("matched workspace capabilities");
        assertThat(decision.strategyRequirements()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "requiresConfirmation", "true",
                "executionPath", "agent_tools"
        ));
        assertThat(decision.decidedAt()).isEqualTo(DECIDED);
        assertThat(decision.decisionSource()).isEqualTo("legacy-agent-decision");
    }
}
