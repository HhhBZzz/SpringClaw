package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RunLifecycleBridgeTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-26T00:00:00Z");

    @Test
    void canonicalBridgeDelegatesToRunCoordinator() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunLifecycleBridge bridge = new DefaultRunLifecycleBridge(
                new RunCoordinator(store)
        );

        bridge.accepted(new RunAcceptance(
                RUN_ID,
                "session-1",
                "api",
                "user-1",
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
        ));
        bridge.contextObserved(
                RUN_ID,
                LegacyLifecycleObserverCanonicalModeTestContext.snapshot(RUN_ID, T0.plusSeconds(1)),
                T0.plusSeconds(1)
        );
        bridge.decisionObserved(
                RUN_ID,
                LegacyLifecycleObserverCanonicalModeTestContext.decision(RUN_ID, T0.plusSeconds(2)),
                T0.plusSeconds(2)
        );
        bridge.executionStarted(RUN_ID, "agent-runtime", T0.plusSeconds(3));

        assertThat(store.requireByRunId(RUN_ID).strategyId())
                .isEqualTo("agent-runtime");
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED
                );
    }
}
