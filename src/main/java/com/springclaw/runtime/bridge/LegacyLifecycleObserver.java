package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.RunState;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public final class LegacyLifecycleObserver {

    private final LegacyRuntimeBridge bridge;
    private final LegacyRunContextAdapter contextAdapter;
    private final LegacyExecutionDecisionAdapter decisionAdapter;
    private final LegacyRunResultAdapter resultAdapter;

    public LegacyLifecycleObserver(
            LegacyRuntimeBridge bridge,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.contextAdapter = Objects.requireNonNull(contextAdapter, "contextAdapter");
        this.decisionAdapter = Objects.requireNonNull(decisionAdapter, "decisionAdapter");
        this.resultAdapter = Objects.requireNonNull(resultAdapter, "resultAdapter");
    }

    public void contextAndDecisionObserved(ChatContext context, Instant at) {
        bridge.contextObserved(
                context.requestId(),
                contextAdapter.adapt(context, at),
                at
        );
        bridge.decisionObserved(
                context.requestId(),
                decisionAdapter.adapt(context, at),
                at
        );
    }

    public void executionStarted(
            ChatContext context,
            String strategyId,
            Instant at
    ) {
        bridge.executionStarted(context.requestId(), strategyId, at);
    }

    public void confirmationRequired(
            String runId,
            String proposalId,
            Instant at
    ) {
        bridge.confirmationRequired(runId, proposalId, at);
    }

    public void confirmationApproved(String runId, Instant at) {
        bridge.confirmationApproved(runId, at);
    }

    public void resultReturned(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer,
            Instant at
    ) {
        bridge.verificationStarted(context.requestId(), at);
        LegacyRunResultAdapter.TerminalObservation observation =
                resultAdapter.adaptDegraded(context, executionResult, answer, at);
        bridge.degraded(
                context.requestId(),
                observation.decision(),
                observation.result(),
                at
        );
    }

    public void failed(
            String runId,
            String failureCode,
            Throwable error,
            Instant at
    ) {
        String message = error == null || error.getMessage() == null
                ? ""
                : error.getMessage();
        bridge.failed(
                runId,
                new RunState.Failure(failureCode, message, false),
                at
        );
    }
}
