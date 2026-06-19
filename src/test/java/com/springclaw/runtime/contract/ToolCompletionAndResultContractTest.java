package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCompletionAndResultContractTest {

    private static final Instant STARTED = Instant.parse("2026-06-19T00:00:01Z");
    private static final Instant FINISHED = Instant.parse("2026-06-19T00:00:02Z");

    @Test
    void toolInvocationFreezesArgumentsPathsAndEvidence() {
        ToolInvocation invocation = invocation(
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );

        assertThat(invocation.targetPaths()).containsExactly("a.txt");
        assertThatThrownBy(() -> invocation.targetPaths().add("b.txt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void waitingConfirmationRequiresProposalId() {
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.WAITING_CONFIRMATION, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposalId");
    }

    @Test
    void runningRequiresStartedAtAndCannotCarryCompletionData() {
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.RUNNING, null, null, null, null
        )).hasMessageContaining("startedAt");

        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.RUNNING, null, STARTED, FINISHED, null
        )).hasMessageContaining("finishedAt");

        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.RUNNING, null, STARTED, null, outcome(true, FINISHED)
        )).hasMessageContaining("outcome");
    }

    @Test
    void unfinishedStatusesCannotCarryFinishedAtOrOutcome() {
        for (ToolInvocation.Status status : List.of(
                ToolInvocation.Status.REQUESTED,
                ToolInvocation.Status.WAITING_CONFIRMATION,
                ToolInvocation.Status.APPROVED
        )) {
            String proposalId = status == ToolInvocation.Status.WAITING_CONFIRMATION
                    ? "proposal-1" : null;
            assertThatThrownBy(() -> invocation(
                    status, proposalId, null, FINISHED, null
            )).as(status.name()).hasMessageContaining("finishedAt");
            assertThatThrownBy(() -> invocation(
                    status, proposalId, null, null, outcome(true, FINISHED)
            )).as(status.name()).hasMessageContaining("outcome");
        }
    }

    @Test
    void succeededRequiresStartedFinishedAndSuccessfulOutcome() {
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.SUCCEEDED, null, null, FINISHED, outcome(true, FINISHED)
        )).hasMessageContaining("startedAt");
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.SUCCEEDED, null, STARTED, null, outcome(true, FINISHED)
        )).hasMessageContaining("finishedAt");
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.SUCCEEDED, null, STARTED, FINISHED, null
        )).hasMessageContaining("outcome");
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.SUCCEEDED, null, STARTED, FINISHED, outcome(false, FINISHED)
        )).hasMessageContaining("success");
    }

    @Test
    void failedAndDeniedRequireFinishedUnsuccessfulOutcomeButStartedAtIsOptional() {
        for (ToolInvocation.Status status : List.of(
                ToolInvocation.Status.FAILED,
                ToolInvocation.Status.DENIED
        )) {
            assertThatThrownBy(() -> invocation(
                    status, null, null, null, outcome(false, FINISHED)
            )).as(status.name()).hasMessageContaining("finishedAt");
            assertThatThrownBy(() -> invocation(
                    status, null, null, FINISHED, null
            )).as(status.name()).hasMessageContaining("outcome");
            assertThatThrownBy(() -> invocation(
                    status, null, null, FINISHED, outcome(true, FINISHED)
            )).as(status.name()).hasMessageContaining("success");

            assertThat(invocation(
                    status, null, null, FINISHED, outcome(false, FINISHED)
            ).startedAt()).isNull();
        }
    }

    @Test
    void outcomeCompletionTimeMustEqualInvocationFinishedAt() {
        assertThatThrownBy(() -> invocation(
                ToolInvocation.Status.SUCCEEDED,
                null,
                STARTED,
                FINISHED,
                outcome(true, FINISHED.plusSeconds(1))
        )).hasMessageContaining("completedAt");
    }

    @Test
    void completionDecisionRejectsRetryWithoutRetryAllowed() {
        assertThatThrownBy(() -> new CompletionDecision(
                "run-1", CompletionDecision.Outcome.RETRY, "retry",
                "try again", List.of(), List.of(), false,
                2, 0.5, FINISHED
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryAllowed");
    }

    @Test
    void completionDecisionConstrainsRetryMetadata() {
        assertThatThrownBy(() -> new CompletionDecision(
                "run-1", CompletionDecision.Outcome.RETRY, "retry",
                "try again", List.of(), List.of("evidence"), true,
                1, 0.5, FINISHED
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextAttempt");
    }

    @Test
    void completionDecisionRejectsNonFiniteOrOutOfRangeQuality() {
        for (double invalid : invalidQualities()) {
            assertThatThrownBy(() -> new CompletionDecision(
                    "run-1", CompletionDecision.Outcome.COMPLETE, "complete",
                    "done", List.of(), List.of(), false,
                    0, invalid, FINISHED
            )).as("quality %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quality");
        }
    }

    @Test
    void runResultRejectsNonFiniteOrOutOfRangeQuality() {
        for (double invalid : invalidQualities()) {
            assertThatThrownBy(() -> result(
                    RunStatus.COMPLETED, RunResult.AnswerKind.FINAL, invalid, ""
            )).as("quality %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quality");
        }
    }

    @Test
    void runResultRequiresStatusSpecificAnswerKindAndFailedCode() {
        assertThatThrownBy(() -> result(
                RunStatus.COMPLETED, RunResult.AnswerKind.DEGRADED, 1.0, ""
        )).hasMessageContaining("FINAL");
        assertThatThrownBy(() -> result(
                RunStatus.DEGRADED, RunResult.AnswerKind.FINAL, 1.0, ""
        )).hasMessageContaining("DEGRADED");
        assertThatThrownBy(() -> result(
                RunStatus.FAILED, RunResult.AnswerKind.FINAL, 1.0, "failed"
        )).hasMessageContaining("FAILURE");
        assertThatThrownBy(() -> result(
                RunStatus.FAILED, RunResult.AnswerKind.FAILURE, 1.0, ""
        )).hasMessageContaining("failureCode");
    }

    @Test
    void runResultRejectsNullAnswerKindWithIllegalArgumentException() {
        assertThatThrownBy(() -> result(
                RunStatus.COMPLETED, null, 1.0, ""
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answerKind");
    }

    private static List<Double> invalidQualities() {
        return List.of(
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                -0.01,
                1.01
        );
    }

    private static ToolInvocation invocation(
            ToolInvocation.Status status,
            String proposalId,
            Instant startedAt,
            Instant finishedAt,
            ToolInvocation.Outcome outcome
    ) {
        return new ToolInvocation(
                "inv-1", "run-1", 1, "workspace", "write-file",
                "workspaceEdit", "workspace-edit", "{\"path\":\"a.txt\"}",
                "sha256", ToolInvocation.RiskLevel.WRITE, List.of("a.txt"),
                List.of("file:a.txt"), "run-1:1:inv-1",
                status, proposalId, startedAt, finishedAt, outcome
        );
    }

    private static ToolInvocation.Outcome outcome(boolean success, Instant completedAt) {
        return new ToolInvocation.Outcome(
                success,
                success ? "ok" : "failed",
                "summary",
                List.of("evidence"),
                completedAt
        );
    }

    private static RunResult result(
            RunStatus status,
            RunResult.AnswerKind answerKind,
            double quality,
            String failureCode
    ) {
        return new RunResult(
                "run-1", status, "answer", answerKind,
                "provider", "model", List.of(), List.of(), quality,
                Map.of(), failureCode, null, FINISHED
        );
    }
}
