package com.springclaw.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateContractTest {

    private static final Instant T0 = Instant.parse("2026-06-19T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-19T00:00:01Z");
    private static final Instant T2 = Instant.parse("2026-06-19T00:00:02Z");
    private static final Instant T3 = Instant.parse("2026-06-19T00:00:03Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void runStateExposesNoBuilderApi() {
        assertThat(Arrays.stream(RunState.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("builder", "toBuilder");
        assertThat(Arrays.stream(RunState.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .doesNotContain("Builder");
    }

    @Test
    void runIdMustEqualRequestId() {
        assertThatThrownBy(() -> state(
                "run-1", "request-2", 0, RunStatus.CREATED, T0, null,
                null, null, "", 1, "", List.of(), null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equal");
    }

    @Test
    void nestedContractRunIdentifiersMustMatchState() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-2"), null, "", 1, "", List.of(), null, null, null
        )).hasMessageContaining("ContextSnapshot");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), decision("run-2"), "", 1, "", List.of(), null, null, null
        )).hasMessageContaining("ExecutionDecision");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(invocation("run-2")), null, null, null
        )).hasMessageContaining("ToolInvocation");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completion("run-2", CompletionDecision.Outcome.RETRY, 2), null, null
        )).hasMessageContaining("CompletionDecision");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 5, RunStatus.COMPLETED, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-2", RunStatus.COMPLETED), null
        )).hasMessageContaining("RunResult");
    }

    @Test
    void toolInvocationListRejectsNullEntriesAsContractViolations() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                Arrays.asList((ToolInvocation) null), null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolInvocations");
    }

    @Test
    void terminalStatesRequireMatchingResultStatusAndCompletionOutcome() {
        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.DEGRADE, 0),
                result("run-1", RunStatus.COMPLETED),
                null
        )).hasMessageContaining("COMPLETE");

        assertThatThrownBy(() -> terminalState(
                RunStatus.DEGRADED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.DEGRADED),
                null
        )).hasMessageContaining("DEGRADE");

        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.DEGRADED),
                null
        )).hasMessageContaining("status");

        assertThatThrownBy(() -> terminalState(
                RunStatus.DEGRADED,
                null,
                result("run-1", RunStatus.DEGRADED),
                null
        )).hasMessageContaining("CompletionDecision");

        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                null,
                null
        )).hasMessageContaining("RunResult");
    }

    @Test
    void nonterminalStateRejectsRunResult() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, result("run-1", RunStatus.COMPLETED), null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonterminal");
    }

    @Test
    void failedRequiresFailureAndFinishedAtButAllowsEarlyFailureWithoutResultOrDecision() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, T1,
                null, null, "", 1, "", List.of(), null, null, null
        )).hasMessageContaining("failure");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, null,
                null, null, "", 1, "", List.of(), null, null, failure()
        )).hasMessageContaining("finishedAt");

        RunState earlyFailure = state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, T1,
                null, null, "", 1, "", List.of(), null, null, failure()
        );
        assertThat(earlyFailure.result()).isNull();
        assertThat(earlyFailure.completionDecision()).isNull();
    }

    @Test
    void failedOptionalResultAndDecisionMustExpressFailure() {
        assertThatThrownBy(() -> terminalState(
                RunStatus.FAILED,
                completion("run-1", CompletionDecision.Outcome.FAIL, 0),
                result("run-1", RunStatus.COMPLETED),
                failure()
        )).hasMessageContaining("status");

        assertThatThrownBy(() -> terminalState(
                RunStatus.FAILED,
                completion("run-1", CompletionDecision.Outcome.DEGRADE, 0),
                result("run-1", RunStatus.FAILED),
                failure()
        )).hasMessageContaining("FAIL");
    }

    @Test
    void waitingConfirmationRequiresPendingProposalId() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.WAITING_CONFIRMATION, T1, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        )).hasMessageContaining("pendingProposalId");
    }

    @Test
    void transitionRetainsIdentityIncrementsRevisionUsesAllowedEdgeAndMonotonicTime() {
        RunState created = createdState();

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-2", "run-2", 1, RunStatus.CONTEXT_READY, T1, null,
                        snapshot("run-2"), null, "", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("identity");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-1", "run-1", 2, RunStatus.CONTEXT_READY, T1, null,
                        snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("revision");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-1", "run-1", 1, RunStatus.RUNNING, T1, null,
                        null, null, "strategy-1", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("CREATED -> RUNNING");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T0.minusSeconds(1), null,
                        snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("updatedAt");
    }

    @Test
    void transitionRequiresEvidenceForContextDecisionAndRunningStages() {
        RunState created = createdState();
        RunState contextReadyWithoutSnapshot = state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                null, null, "", 1, "", List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(created, contextReadyWithoutSnapshot))
                .hasMessageContaining("contextSnapshot");

        RunState contextReady = contextReadyState();
        RunState decidedWithoutDecision = state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(contextReady, decidedWithoutDecision))
                .hasMessageContaining("executionDecision");

        RunState decided = decidedState();
        RunState runningWithoutStrategy = state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "", 1, "",
                List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(decided, runningWithoutStrategy))
                .hasMessageContaining("strategyId");
    }

    @Test
    void verifyingRetryRequiresRetryDecisionAndMatchingNextAttempt() {
        RunState retrying = verifyingState(
                1,
                completion("run-1", CompletionDecision.Outcome.RETRY, 2)
        );
        RunState nextAttempt = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 2, "",
                List.of(), null, null, null
        );
        RunTransitionPolicy.validate(retrying, nextAttempt);

        RunState noDecision = verifyingState(1, null);
        assertThatThrownBy(() -> RunTransitionPolicy.validate(noDecision, nextAttempt))
                .hasMessageContaining("RETRY");

        RunState wrongOutcome = verifyingState(
                1,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0)
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(wrongOutcome, nextAttempt))
                .hasMessageContaining("RETRY");

        RunState skippedAttempt = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 3, "",
                List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(retrying, skippedAttempt))
                .hasMessageContaining("attempt");

        RunState mismatchedDecisionAttempt = verifyingState(
                1,
                completion("run-1", CompletionDecision.Outcome.RETRY, 3)
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(mismatchedDecisionAttempt, nextAttempt))
                .hasMessageContaining("nextAttempt");
    }

    @Test
    void runningToVerifyingAndConfirmationResumeNeedNoInventedEvidence() {
        RunState running = runningState();
        RunState verifying = state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        );
        RunTransitionPolicy.validate(running, verifying);

        RunState waiting = state(
                "run-1", "run-1", 4, RunStatus.WAITING_CONFIRMATION, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "proposal-1",
                List.of(), null, null, null
        );
        RunState resumed = state(
                "run-1", "run-1", 5, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        );
        RunTransitionPolicy.validate(waiting, resumed);
    }

    @Test
    void failedTransitionRequiresFailureAndTerminalStatesRemainImmutable() {
        RunState failed = state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, T1,
                null, null, "", 1, "", List.of(), null, null, failure()
        );
        RunTransitionPolicy.validate(createdState(), failed);

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                completedState(),
                completedState()
        )).hasMessageContaining("terminal");
    }

    @Test
    void terminalRunStateSurvivesJacksonRoundTrip() throws Exception {
        RunState completed = completedState();
        String json = mapper.writeValueAsString(completed);
        RunState deserialized = mapper.readValue(json, RunState.class);
        assertThat(deserialized).isEqualTo(completed);
    }

    private static RunState createdState() {
        return state(
                "run-1", "run-1", 0, RunStatus.CREATED, T0, null,
                null, null, "", 1, "", List.of(), null, null, null
        );
    }

    private static RunState contextReadyState() {
        return state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
        );
    }

    private static RunState decidedState() {
        return state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), decision("run-1"), "", 1, "",
                List.of(), null, null, null
        );
    }

    private static RunState runningState() {
        return state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        );
    }

    private static RunState verifyingState(int attempt, CompletionDecision completionDecision) {
        return state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", attempt, "",
                List.of(), completionDecision, null, null
        );
    }

    private static RunState completedState() {
        return terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.COMPLETED),
                null
        );
    }

    private static RunState terminalState(
            RunStatus status,
            CompletionDecision completionDecision,
            RunResult result,
            RunState.Failure failure
    ) {
        return state(
                "run-1", "run-1", 5, status, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completionDecision, result, failure
        );
    }

    private static RunState state(
            String runId,
            String requestId,
            long revision,
            RunStatus status,
            Instant updatedAt,
            Instant finishedAt,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision,
            String strategyId,
            int attempt,
            String pendingProposalId,
            List<ToolInvocation> toolInvocations,
            CompletionDecision completionDecision,
            RunResult result,
            RunState.Failure failure
    ) {
        return new RunState(
                runId,
                requestId,
                revision,
                status,
                "session-1",
                "web",
                "user-1",
                "USER",
                "hello",
                "agent",
                T0,
                status == RunStatus.CREATED ? null : T1,
                updatedAt,
                finishedAt,
                T3.plusSeconds(30),
                contextSnapshot,
                executionDecision,
                strategyId,
                attempt,
                pendingProposalId,
                toolInvocations,
                completionDecision,
                result,
                Map.of(),
                failure
        );
    }

    private static ContextSnapshot snapshot(String runId) {
        return new ContextSnapshot(
                runId, "session-1", "user-1", "web", "user-1", "USER",
                "original", "effective", "system", "memory",
                List.of(), List.of(), List.of(), List.of("web.search"),
                Map.of(), Map.of("schema", "v1"), T0, "hash-1"
        );
    }

    private static ExecutionDecision decision(String runId) {
        return new ExecutionDecision(
                runId, "research", "answer", "agent", "read",
                List.of("web.search"), List.of(), Map.of(), List.of(),
                0.8, "matched capability", "policy", T0
        );
    }

    private static ToolInvocation invocation(String runId) {
        return new ToolInvocation(
                "inv-1", runId, 1, "web.search", "search",
                "search", "web", "{}", "hash", ToolInvocation.RiskLevel.READ,
                List.of(), List.of(), runId + ":1:inv-1",
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );
    }

    private static CompletionDecision completion(
            String runId,
            CompletionDecision.Outcome outcome,
            int nextAttempt
    ) {
        return new CompletionDecision(
                runId, outcome, outcome.name().toLowerCase(), "summary",
                List.of(), List.of(), outcome == CompletionDecision.Outcome.RETRY,
                nextAttempt, 1.0, T3
        );
    }

    private static RunResult result(String runId, RunStatus status) {
        RunResult.AnswerKind answerKind = switch (status) {
            case COMPLETED -> RunResult.AnswerKind.FINAL;
            case DEGRADED -> RunResult.AnswerKind.DEGRADED;
            case FAILED -> RunResult.AnswerKind.FAILURE;
            default -> throw new IllegalArgumentException("terminal status required");
        };
        return new RunResult(
                runId, status, "answer", answerKind,
                "provider", "model", List.of(), List.of(), 1.0,
                Map.of(), status == RunStatus.FAILED ? "failed" : "", null, T3
        );
    }

    private static RunState.Failure failure() {
        return new RunState.Failure("failed", "failure", false);
    }
}
