package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCompletionAndResultContractTest {

    @Test
    void toolInvocationFreezesArgumentsPathsAndEvidence() {
        ToolInvocation invocation = new ToolInvocation(
                "inv-1", "run-1", 1, "workspace", "write-file",
                "workspaceEdit", "workspace-edit", "{\"path\":\"a.txt\"}",
                "sha256", ToolInvocation.RiskLevel.WRITE, List.of("a.txt"),
                List.of("file:a.txt"), "run-1:1:inv-1",
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );

        assertThat(invocation.targetPaths()).containsExactly("a.txt");
        assertThatThrownBy(() -> invocation.targetPaths().add("b.txt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolInvocationRequiresProposalIdWhenWaitingConfirmation() {
        assertThatThrownBy(() -> new ToolInvocation(
                "inv-1", "run-1", 1, "workspace", "write-file",
                "workspaceEdit", "workspace-edit", "{\"path\":\"a.txt\"}",
                "sha256", ToolInvocation.RiskLevel.WRITE, List.of("a.txt"),
                List.of("file:a.txt"), "run-1:1:inv-1",
                ToolInvocation.Status.WAITING_CONFIRMATION, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposalId");
    }

    @Test
    void completionDecisionRejectsRetryWithoutRetryAllowed() {
        assertThatThrownBy(() -> new CompletionDecision(
                "run-1", CompletionDecision.Outcome.RETRY, "retry",
                "try again", List.of(), List.of(), false,
                2, 0.5, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryAllowed");
    }

    @Test
    void completionDecisionConstrainsRetryMetadata() {
        assertThatThrownBy(() -> new CompletionDecision(
                "run-1", CompletionDecision.Outcome.RETRY, "retry",
                "try again", List.of(), List.of("evidence"), true,
                1, 0.5, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextAttempt");
    }

    @Test
    void failedResultRequiresFailureCode() {
        assertThatThrownBy(() -> new RunResult(
                "run-1", RunStatus.FAILED, "answer", RunResult.AnswerKind.FINAL,
                "provider", "model", List.of(), List.of(), 1.0,
                Map.of(), null, null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
    }

    @Test
    void failureAnswerKindRequiresFailedStatus() {
        assertThatThrownBy(() -> new RunResult(
                "run-1", RunStatus.COMPLETED, "answer", RunResult.AnswerKind.FAILURE,
                "provider", "model", List.of(), List.of(), 1.0,
                Map.of(), "", null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAILURE");
    }
}
