package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public class DefaultRunLifecycleBridge implements RunLifecycleBridge {

    private final RunCoordinator coordinator;

    public DefaultRunLifecycleBridge(RunCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public RunState accepted(RunAcceptance acceptance) {
        return coordinator.accept(acceptance);
    }

    @Override
    public RunState contextObserved(
            String runId,
            ContextSnapshot snapshot,
            Instant at
    ) {
        return coordinator.contextReady(runId, snapshot, at);
    }

    @Override
    public RunState decisionObserved(
            String runId,
            ExecutionDecision decision,
            Instant at
    ) {
        return coordinator.decided(runId, decision, at);
    }

    @Override
    public RunState executionStarted(
            String runId,
            String strategyId,
            Instant at
    ) {
        return coordinator.running(runId, strategyId, at);
    }

    @Override
    public RunState confirmationRequired(
            String runId,
            String proposalId,
            Instant at
    ) {
        return coordinator.waitingConfirmation(runId, proposalId, at);
    }

    @Override
    public RunState confirmationApproved(String runId, Instant at) {
        return coordinator.confirmationApproved(runId, at);
    }

    @Override
    public RunState confirmationRejected(
            String runId,
            RunState.Failure failure,
            Instant at
    ) {
        return coordinator.confirmationRejected(runId, failure, at);
    }

    @Override
    public void toolStarted(String runId, Instant at) {
        coordinator.toolStarted(runId, at);
    }

    @Override
    public void toolSucceeded(String runId, Instant at) {
        coordinator.toolSucceeded(runId, at);
    }

    @Override
    public void toolFailed(String runId, Instant at) {
        coordinator.toolFailed(runId, at);
    }

    @Override
    public RunState verificationStarted(String runId, Instant at) {
        return coordinator.verifying(runId, at);
    }

    @Override
    public RunState completed(
            String runId,
            CompletionDecision decision,
            RunResult result,
            Instant at
    ) {
        return coordinator.completed(runId, decision, result, at);
    }

    @Override
    public RunState degraded(
            String runId,
            CompletionDecision decision,
            RunResult result,
            Instant at
    ) {
        return coordinator.degraded(runId, decision, result, at);
    }

    @Override
    public RunState failed(String runId, RunState.Failure failure, Instant at) {
        return coordinator.failed(runId, failure, at);
    }

    @Override
    public RunState failed(
            String runId,
            CompletionDecision decision,
            RunState.Failure failure,
            Instant at
    ) {
        return coordinator.failed(runId, decision, failure, at);
    }
}
