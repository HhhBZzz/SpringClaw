package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunCoordinatorTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-21T00:00:00Z");

    private final InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
    private final RunCoordinator coordinator = new RunCoordinator(store);

    @Test
    void coordinatesAcceptedRunThroughConfirmationAndCompletion() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.waitingConfirmation(RUN_ID, "proposal-1", T0.plusSeconds(4));
        coordinator.confirmationApproved(RUN_ID, T0.plusSeconds(5));
        coordinator.verifying(RUN_ID, T0.plusSeconds(6));
        coordinator.completed(
                RUN_ID,
                completion(CompletionDecision.Outcome.COMPLETE, T0.plusSeconds(7)),
                result(RunStatus.COMPLETED, T0.plusSeconds(7)),
                T0.plusSeconds(7)
        );

        RunState state = store.requireByRunId(RUN_ID);
        assertThat(state.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.revision()).isEqualTo(7);
        assertThat(state.sessionAccessClaim()).isEqualTo(acceptance().sessionAccessClaim());
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.CONFIRMATION_REQUIRED,
                        RunEventType.CONFIRMATION_APPROVED,
                        RunEventType.VERIFICATION_COMPLETED,
                        RunEventType.RUN_COMPLETED
                );
    }

    @Test
    void coordinatesDegradedAndFailedTerminalOutcomes() {
        prepareVerifyingRun();
        coordinator.degraded(
                RUN_ID,
                completion(CompletionDecision.Outcome.DEGRADE, T0.plusSeconds(5)),
                result(RunStatus.DEGRADED, T0.plusSeconds(5)),
                T0.plusSeconds(5)
        );
        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.DEGRADED);

        String failedRunId = "11111111111111111111111111111111";
        coordinator.accept(acceptance(failedRunId));
        coordinator.failed(
                failedRunId,
                new RunState.Failure("CONTEXT_FAILED", "context unavailable", true),
                T0.plusSeconds(1)
        );
        assertThat(store.requireByRunId(failedRunId).status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void verifyingFailureRequiresAndPersistsFailCompletionDecision() {
        prepareVerifyingRun();
        CompletionDecision failureDecision = completion(
                CompletionDecision.Outcome.FAIL,
                T0.plusSeconds(5)
        );

        coordinator.failed(
                RUN_ID,
                failureDecision,
                new RunState.Failure("VERIFICATION_FAILED", "insufficient evidence", false),
                T0.plusSeconds(5)
        );

        RunState failed = store.requireByRunId(RUN_ID);
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.completionDecision()).isEqualTo(failureDecision);
    }

    @Test
    void rejectsMutationAfterTerminalState() {
        String runId = "22222222222222222222222222222222";
        coordinator.accept(acceptance(runId));
        coordinator.failed(
                runId,
                new RunState.Failure("REJECTED", "rejected", false),
                T0.plusSeconds(1)
        );

        assertThatThrownBy(() -> coordinator.failed(
                runId,
                new RunState.Failure("SECOND", "second", false),
                T0.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void appendsToolFactsWithoutChangingStateRevision() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        RunState running = coordinator.running(
                RUN_ID, "agent-runtime", T0.plusSeconds(3)
        );

        coordinator.toolStarted(RUN_ID, T0.plusSeconds(4));
        coordinator.toolSucceeded(RUN_ID, T0.plusSeconds(5));

        assertThat(store.requireByRunId(RUN_ID)).isEqualTo(running);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .endsWith(RunEventType.TOOL_STARTED, RunEventType.TOOL_SUCCEEDED);
    }

    @Test
    void confirmationRejectionFailsRunWithTypedBoundaryEvent() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.waitingConfirmation(RUN_ID, "proposal-1", T0.plusSeconds(4));

        coordinator.confirmationRejected(
                RUN_ID,
                new RunState.Failure(
                        "CONFIRMATION_REJECTED", "rejected by user", false
                ),
                T0.plusSeconds(5)
        );

        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.FAILED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .endsWith(RunEventType.CONFIRMATION_REJECTED);
    }

    @Test
    void confirmationRejectionCannotTerminateRunThatIsNotWaiting() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));

        assertThatThrownBy(() -> coordinator.confirmationRejected(
                RUN_ID,
                new RunState.Failure(
                        "CONFIRMATION_REJECTED", "stale rejection", false
                ),
                T0.plusSeconds(4)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITING_CONFIRMATION");
        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void confirmationApprovalCannotResumeRunThatIsNotWaiting() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));

        assertThatThrownBy(() -> coordinator.confirmationApproved(
                RUN_ID,
                T0.plusSeconds(3)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITING_CONFIRMATION");
        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.DECIDED);
    }

    @Test
    void acceptanceRejectsSessionAccessClaimMismatch() {
        assertThatThrownBy(() -> new RunAcceptance(
                RUN_ID,
                "session-1",
                "api",
                "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "web",
                        "session-1",
                        "user-1"
                ),
                "USER",
                "hello",
                "agent",
                T0,
                T0.plusSeconds(300)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void acceptanceNormalizesIdentityBeforeComparingClaim() {
        RunAcceptance acceptance = new RunAcceptance(
                RUN_ID,
                " session-1 ",
                " api ",
                " user-1 ",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                ),
                "USER",
                "hello",
                "agent",
                T0,
                T0.plusSeconds(300)
        );

        assertThat(acceptance.sessionKey()).isEqualTo("session-1");
        assertThat(acceptance.channel()).isEqualTo("api");
        assertThat(acceptance.userId()).isEqualTo("user-1");
    }

    private void prepareVerifyingRun() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.verifying(RUN_ID, T0.plusSeconds(4));
    }

    private static RunAcceptance acceptance() {
        return acceptance(RUN_ID);
    }

    private static RunAcceptance acceptance(String runId) {
        return new RunAcceptance(
                runId, "session-1", "api", "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                ),
                "USER", "hello",
                "agent", T0, T0.plusSeconds(300)
        );
    }

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                RUN_ID, "session-1", "user-1", "api", "user-1", "USER",
                "hello", "hello", "system", "", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), memoryFrame(RUN_ID), T0.plusSeconds(1), "snapshot-hash"
        );
    }

    private static MemoryFrame memoryFrame(String runId) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api", "session-1", "user-1"
                )),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                java.util.Map.of("source", "legacy-test"), List.of(),
                java.time.Instant.parse("2026-06-24T00:00:00Z"), "frame-hash-" + runId
        );
    }

    private static ExecutionDecision decision() {
        return new ExecutionDecision(
                RUN_ID, "general", "answer", "agent", "read", List.of(),
                List.of(), Map.of(), List.of(), 1.0, "legacy", "legacy",
                T0.plusSeconds(2)
        );
    }

    private static CompletionDecision completion(
            CompletionDecision.Outcome outcome,
            Instant at
    ) {
        return new CompletionDecision(
                RUN_ID, outcome, "LEGACY_" + outcome, "legacy", List.of("legacy"),
                List.of(), false, 0, 0.8, at
        );
    }

    private static RunResult result(RunStatus status, Instant at) {
        return new RunResult(
                RUN_ID, status, "answer",
                status == RunStatus.COMPLETED
                        ? RunResult.AnswerKind.FINAL
                        : RunResult.AnswerKind.DEGRADED,
                "legacy", "legacy", List.of("legacy"), List.of(), 0.8,
                Map.of(), "", "", at
        );
    }
}
