package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshotRequest;
import com.springclaw.runtime.contract.RunState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RunStateContextSnapshotRequestFactory {

    public ContextSnapshotRequest create(
            RunState runState,
            String effectiveMessage,
            String systemPrompt,
            List<String> allowedCapabilities,
            Map<String, String> providerSnapshot
    ) {
        Objects.requireNonNull(runState, "runState");
        if (runState.status().isTerminal()) {
            throw new IllegalStateException(
                    "terminal run cannot create ContextSnapshotRequest: "
                            + runState.status()
            );
        }
        return new ContextSnapshotRequest(
                runState.runId(),
                runState.sessionKey(),
                runState.userId(),
                runState.channel(),
                runState.userId(),
                runState.sessionAccessClaim(),
                runState.roleCodeAtAcceptance(),
                runState.originalMessage(),
                effectiveMessage == null ? "" : effectiveMessage,
                systemPrompt == null ? "" : systemPrompt,
                allowedCapabilities == null ? List.of() : allowedCapabilities,
                providerSnapshot == null ? Map.of() : providerSnapshot
        );
    }
}
