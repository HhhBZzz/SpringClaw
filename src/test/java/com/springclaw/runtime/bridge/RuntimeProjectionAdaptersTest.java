package com.springclaw.runtime.bridge;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProjectionAdaptersTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant AT = Instant.parse("2026-06-22T00:00:00Z");

    @Test
    void contextAdapterUsesAlreadyAssembledLegacyContextAndProducesStableHash() {
        ChatContext context = context();
        RollbackRunContextAdapter adapter = new RollbackRunContextAdapter();

        ContextSnapshot first = adapter.adapt(context, AT);
        ContextSnapshot second = adapter.adapt(context, AT);

        assertThat(first.runId()).isEqualTo(RUN_ID);
        assertThat(first.originalMessage()).isEqualTo("original");
        assertThat(first.effectiveMessage()).isEqualTo("effective");
        assertThat(first.shortTermEvents()).containsExactly("short-term");
        assertThat(first.semanticRecallItems()).containsExactly("semantic");
        assertThat(first.allowedCapabilities()).containsExactly("web");
        assertThat(first.providerSnapshot())
                .containsEntry("providerId", "provider")
                .containsEntry("model", "model");
        assertThat(first.snapshotHash()).isEqualTo(second.snapshotHash());
    }

    @Test
    void decisionAdapterTranslatesExistingDecisionWithoutSelectingAgain() {
        ExecutionDecision decision = new RunExecutionDecisionProjector().adapt(
                context(),
                AT
        );

        assertThat(decision.runId()).isEqualTo(RUN_ID);
        assertThat(decision.intent()).isEqualTo("web_research");
        assertThat(decision.riskSummary()).isEqualTo("read");
        assertThat(decision.selectedCapabilityIds()).containsExactly("web");
        assertThat(decision.strategyRequirements())
                .containsEntry("executionPath", "agent_tools")
                .containsEntry("legacyExecutionMode", "simplified");
        assertThat(decision.decisionSource()).isEqualTo("legacy-agent-decision");
    }

    @Test
    void resultAdapterProjectsVerdictOutcomeIntoTerminalObservation() {
        // DEGRADE 判定 → DEGRADED 终态 + missingEvidence 标记
        RunResultProjector.TerminalObservation degraded =
                new RunResultProjector().adaptTerminal(
                        context(),
                        new ChatExecutionResult(
                                "observe", "plan", "action", "reflect", false
                        ),
                        "fallback answer",
                        new CompletionVerdict(
                                CompletionDecision.Outcome.DEGRADE,
                                "MODEL_UNAVAILABLE_LOCAL_FALLBACK",
                                "模型不可用"
                        ),
                        AT
                );

        assertThat(degraded.decision().outcome())
                .isEqualTo(CompletionDecision.Outcome.DEGRADE);
        assertThat(degraded.decision().reasonCode())
                .isEqualTo("MODEL_UNAVAILABLE_LOCAL_FALLBACK");
        assertThat(degraded.decision().missingEvidence())
                .containsExactly("canonical-completion-verification");
        assertThat(degraded.result().status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(degraded.result().answer()).isEqualTo("fallback answer");
        assertThat(degraded.result().quality()).isZero();

        // COMPLETE 判定 → COMPLETED 终态,不再强制 LEGACY_UNVERIFIED_RESULT
        RunResultProjector.TerminalObservation completed =
                new RunResultProjector().adaptTerminal(
                        context(),
                        new ChatExecutionResult(
                                "observe", "plan", "action", "reflect", true
                        ),
                        "model answer",
                        new CompletionVerdict(
                                CompletionDecision.Outcome.COMPLETE,
                                "MODEL_VERIFIED_ANSWER",
                                "模型产出非空回答"
                        ),
                        AT
                );

        assertThat(completed.decision().outcome())
                .isEqualTo(CompletionDecision.Outcome.COMPLETE);
        assertThat(completed.decision().missingEvidence()).isEmpty();
        assertThat(completed.result().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.result().answerKind())
                .isEqualTo(com.springclaw.runtime.contract.RunResult.AnswerKind.FINAL);
    }

    private static ChatContext context() {
        AgentSession session = new AgentSession();
        session.setSessionKey("session-1");
        session.setUserId("user-1");
        session.setChannel("api");
        AssembledContext assembled = new AssembledContext(
                "session-1", "api", "user-1", "effective",
                "short-term", "semantic", "observe-prompt"
        );
        AiProviderService.ActiveChatClient activeClient =
                new AiProviderService.ActiveChatClient(
                        "provider", "model", "https://example.test",
                        null, true, ""
                );
        return new ChatContext(
                session, "api", "user-1", "USER", "original", "effective",
                RUN_ID, "system", assembled, activeClient, "simplified",
                "legacy routing", "agent", "web_research",
                new AgentDecision(
                        "web_research", "agent_tools", List.of("web"),
                        "read", false, "legacy decision"
                ),
                new ContextInjection(
                        "observe-prompt", "", "",
                        Map.of("contextSummary", assembled.sourceSummary())
                )
        );
    }
}
