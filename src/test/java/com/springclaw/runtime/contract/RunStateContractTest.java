package com.springclaw.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateContractTest {

    private static final Instant T0 = Instant.parse("2026-06-19T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-19T00:00:01Z");
    private static final Instant T2 = Instant.parse("2026-06-19T00:00:02Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ---------- aggregate invariants ----------

    @Test
    void runIdMustEqualRequestId() {
        assertThatThrownBy(() -> createdState("run-1", "request-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equal");
    }

    @Test
    void waitingConfirmationRequiresPendingProposalId() {
        assertThatThrownBy(() -> waitingState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingProposalId");
    }

    @Test
    void completedRequiresRunResult() {
        assertThatThrownBy(() -> completedState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RunResult");
    }

    @Test
    void failedRequiresFailure() {
        assertThatThrownBy(() -> failedState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure");
    }

    // ---------- transition policy ----------

    @Test
    void terminalStateIsImmutable() {
        assertThatThrownBy(() -> RunTransitionPolicy.validate(completedState(result()), completedState(result())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void disallowedTransitionIsRejected() {
        assertThatThrownBy(() -> RunTransitionPolicy.validate(createdState(), runningState()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATED -> RUNNING");
    }

    @Test
    void validTransitionPasses() {
        RunState created = createdState();
        RunState contextReady = created.toBuilder()
                .revision(1)
                .status(RunStatus.CONTEXT_READY)
                .updatedAt(T1)
                .contextSnapshot(snapshot("run-1"))
                .build();
        // Should not throw.
        RunTransitionPolicy.validate(created, contextReady);
    }

    @Test
    void revisionMustIncreaseByExactlyOne() {
        RunState created = createdState();
        RunState jumped = created.toBuilder()
                .revision(2)
                .status(RunStatus.CONTEXT_READY)
                .updatedAt(T1)
                .contextSnapshot(snapshot("run-1"))
                .build();
        assertThatThrownBy(() -> RunTransitionPolicy.validate(created, jumped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision");
    }

    // ---------- jackson round-trip ----------

    @Test
    void terminalRunStateSurvivesJacksonRoundTrip() throws Exception {
        RunState completed = completedState(result());
        String json = mapper.writeValueAsString(completed);
        RunState deserialized = mapper.readValue(json, RunState.class);
        assertThat(deserialized).isEqualTo(completed);
    }

    // ---------- fixtures ----------

    private static ContextSnapshot snapshot(String runId) {
        return new ContextSnapshot(
                runId, "session-1", "user-1", "web", "user-1", "USER",
                "original", "effective", "system", "memory",
                List.of(), List.of(), List.of(), List.of("web.search"),
                Map.of(), Map.of("schema", "v1"), T0, "hash-1");
    }

    private static ExecutionDecision decision(String runId) {
        return new ExecutionDecision(
                runId, "research", "answer", "agent", "read",
                List.of("web.search"), List.of(), Map.of(), List.of(),
                0.8, "matched capability", "policy", T0);
    }

    private static RunResult result() {
        return new RunResult(
                "run-1", RunStatus.COMPLETED, "answer", RunResult.AnswerKind.FINAL,
                "provider", "model", List.of(), List.of(), 1.0,
                Map.of(), "", null, T2);
    }

    private static RunState createdState() {
        return createdState("run-1", "run-1");
    }

    private static RunState createdState(String runId, String requestId) {
        return RunState.builder()
                .runId(runId)
                .requestId(requestId)
                .revision(0)
                .status(RunStatus.CREATED)
                .sessionKey("session-1")
                .channel("web")
                .userId("user-1")
                .roleCodeAtAcceptance("USER")
                .originalMessage("hello")
                .responseMode("agent")
                .acceptedAt(T0)
                .updatedAt(T0)
                .deadlineAt(T2)
                .attempt(1)
                .build();
    }

    private static RunState waitingState(String proposalId) {
        return createdState().toBuilder()
                .revision(1)
                .status(RunStatus.WAITING_CONFIRMATION)
                .pendingProposalId(proposalId)
                .updatedAt(T1)
                .build();
    }

    private static RunState runningState() {
        return createdState().toBuilder()
                .revision(1)
                .status(RunStatus.RUNNING)
                .strategyId("strategy-1")
                .updatedAt(T1)
                .build();
    }

    private static RunState completedState(RunResult result) {
        return runningState().toBuilder()
                .revision(2)
                .status(RunStatus.COMPLETED)
                .result(result)
                .finishedAt(T2)
                .updatedAt(T2)
                .build();
    }

    private static RunState failedState(RunState.Failure failure) {
        return runningState().toBuilder()
                .revision(2)
                .status(RunStatus.FAILED)
                .failure(failure)
                .finishedAt(T2)
                .updatedAt(T2)
                .build();
    }
}
