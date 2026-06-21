package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.lifecycle.RunAcceptance;

import java.time.Instant;

public interface LegacyRuntimeBridge {

    RunState accepted(RunAcceptance acceptance);

    RunState contextObserved(String runId, ContextSnapshot snapshot, Instant at);

    RunState decisionObserved(String runId, ExecutionDecision decision, Instant at);

    RunState executionStarted(String runId, String strategyId, Instant at);

    RunState confirmationRequired(String runId, String proposalId, Instant at);

    RunState confirmationApproved(String runId, Instant at);

    RunState verificationStarted(String runId, Instant at);

    RunState completed(
            String runId,
            CompletionDecision decision,
            RunResult result,
            Instant at
    );

    RunState degraded(
            String runId,
            CompletionDecision decision,
            RunResult result,
            Instant at
    );

    RunState failed(String runId, RunState.Failure failure, Instant at);

    RunState failed(
            String runId,
            CompletionDecision decision,
            RunState.Failure failure,
            Instant at
    );
}
