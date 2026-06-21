package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public final class RunCoordinator {

    private static final String PAYLOAD_SCHEMA = "springclaw.runtime.lifecycle.v1";

    private final RunLifecycleStore store;

    public RunCoordinator(RunLifecycleStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public RunState accept(RunAcceptance acceptance) {
        RunState created = new RunState(
                acceptance.runId(), acceptance.runId(), 0, RunStatus.CREATED,
                acceptance.sessionKey(), acceptance.channel(), acceptance.userId(),
                acceptance.roleCodeAtAcceptance(), acceptance.originalMessage(),
                acceptance.responseMode(), acceptance.acceptedAt(), null,
                acceptance.acceptedAt(), null, acceptance.deadlineAt(),
                null, null, "", 1, "", List.of(), null, null, Map.of(), null
        );
        return store.create(
                created,
                event(created, RunEventType.RUN_CREATED, acceptance.acceptedAt())
        );
    }

    public RunState contextReady(
            String runId,
            ContextSnapshot contextSnapshot,
            Instant at
    ) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, RunStatus.CONTEXT_READY, at, current.startedAt(), null,
                        contextSnapshot, current.executionDecision(), current.strategyId(),
                        current.pendingProposalId(), current.completionDecision(),
                        current.result(), current.usage(), current.failure()),
                RunEventType.CONTEXT_READY,
                at
        );
    }

    public RunState decided(
            String runId,
            ExecutionDecision executionDecision,
            Instant at
    ) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, RunStatus.DECIDED, at, current.startedAt(), null,
                        current.contextSnapshot(), executionDecision, "",
                        current.pendingProposalId(), current.completionDecision(),
                        current.result(), current.usage(), current.failure()),
                RunEventType.DECISION_MADE,
                at
        );
    }

    public RunState running(String runId, String strategyId, Instant at) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, RunStatus.RUNNING, at, at, null,
                        current.contextSnapshot(), current.executionDecision(), strategyId,
                        "", current.completionDecision(), current.result(),
                        current.usage(), current.failure()),
                RunEventType.STRATEGY_STARTED,
                at
        );
    }

    public RunState waitingConfirmation(
            String runId,
            String proposalId,
            Instant at
    ) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, RunStatus.WAITING_CONFIRMATION, at, current.startedAt(), null,
                        current.contextSnapshot(), current.executionDecision(),
                        current.strategyId(), proposalId, current.completionDecision(),
                        current.result(), current.usage(), current.failure()),
                RunEventType.CONFIRMATION_REQUIRED,
                at
        );
    }

    public RunState confirmationApproved(String runId, Instant at) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, RunStatus.RUNNING, at, current.startedAt(), null,
                        current.contextSnapshot(), current.executionDecision(),
                        current.strategyId(), "", current.completionDecision(),
                        current.result(), current.usage(), current.failure()),
                RunEventType.CONFIRMATION_APPROVED,
                at
        );
    }

    public RunState verifying(String runId, Instant at) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, RunStatus.VERIFYING, at, current.startedAt(), null,
                        current.contextSnapshot(), current.executionDecision(),
                        current.strategyId(), current.pendingProposalId(),
                        current.completionDecision(), current.result(),
                        current.usage(), current.failure()),
                RunEventType.VERIFICATION_COMPLETED,
                at
        );
    }

    public RunState completed(
            String runId,
            CompletionDecision completionDecision,
            RunResult result,
            Instant at
    ) {
        return terminal(
                runId, RunStatus.COMPLETED, completionDecision, result, null,
                RunEventType.RUN_COMPLETED, at
        );
    }

    public RunState degraded(
            String runId,
            CompletionDecision completionDecision,
            RunResult result,
            Instant at
    ) {
        return terminal(
                runId, RunStatus.DEGRADED, completionDecision, result, null,
                RunEventType.RUN_DEGRADED, at
        );
    }

    public RunState failed(String runId, RunState.Failure failure, Instant at) {
        return failed(runId, null, failure, at);
    }

    public RunState failed(
            String runId,
            CompletionDecision completionDecision,
            RunState.Failure failure,
            Instant at
    ) {
        return terminal(
                runId, RunStatus.FAILED, completionDecision, null,
                Objects.requireNonNull(failure, "failure"), RunEventType.RUN_FAILED, at
        );
    }

    private RunState terminal(
            String runId,
            RunStatus status,
            CompletionDecision completionDecision,
            RunResult result,
            RunState.Failure failure,
            RunEventType eventType,
            Instant at
    ) {
        RunState current = require(runId);
        return commit(
                current,
                copy(current, status, at, current.startedAt(), at,
                        current.contextSnapshot(), current.executionDecision(),
                        current.strategyId(), current.pendingProposalId(),
                        completionDecision, result,
                        result == null ? current.usage() : result.usage(), failure),
                eventType,
                at
        );
    }

    private RunState require(String runId) {
        RunState current = store.requireByRunId(runId);
        if (current.status().isTerminal()) {
            throw new IllegalStateException(
                    "terminal run state is immutable: " + current.status()
            );
        }
        return current;
    }

    private RunState commit(
            RunState current,
            RunState next,
            RunEventType eventType,
            Instant at
    ) {
        return store.commit(
                current.revision(),
                next,
                event(next, eventType, at)
        );
    }

    private static RunState copy(
            RunState current,
            RunStatus status,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision,
            String strategyId,
            String pendingProposalId,
            CompletionDecision completionDecision,
            RunResult result,
            Map<String, Long> usage,
            RunState.Failure failure
    ) {
        return new RunState(
                current.runId(), current.requestId(), current.revision() + 1, status,
                current.sessionKey(), current.channel(), current.userId(),
                current.roleCodeAtAcceptance(), current.originalMessage(),
                current.responseMode(), current.acceptedAt(), startedAt, updatedAt,
                finishedAt, current.deadlineAt(), contextSnapshot, executionDecision,
                strategyId, current.attempt(), pendingProposalId,
                current.toolInvocations(), completionDecision, result, usage, failure
        );
    }

    private static RunEvent.Draft event(
            RunState state,
            RunEventType eventType,
            Instant at
    ) {
        return new RunEvent.Draft(
                state.runId(), eventType, "lifecycle", state.status(), at, 0,
                PAYLOAD_SCHEMA, "{}", null, state.requestId()
        );
    }
}
