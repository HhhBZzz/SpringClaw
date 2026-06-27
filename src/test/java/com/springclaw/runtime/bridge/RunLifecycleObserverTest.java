package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RunLifecycleObserverTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-26T00:00:00Z");

    @Test
    void canonicalModeSkipsContextObservationButKeepsDecisionObservation() {
        RunLifecycleBridge bridge = mock(RunLifecycleBridge.class);
        RunLifecycleObserver observer = new RunLifecycleObserver(
                bridge,
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                true
        );
        ChatContext context = RunLifecycleObserverTestContext.context(RUN_ID);

        observer.contextAndDecisionObserved(context, T0);

        verify(bridge, never()).contextObserved(any(), any(), any());
        verify(bridge).decisionObserved(eq(RUN_ID), any(), eq(T0));
    }

    @Test
    void rollbackModeKeepsContextAndDecisionObservation() {
        RunLifecycleBridge bridge = mock(RunLifecycleBridge.class);
        RunLifecycleObserver observer = new RunLifecycleObserver(
                bridge,
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                false
        );
        ChatContext context = RunLifecycleObserverTestContext.context(RUN_ID);

        observer.contextAndDecisionObserved(context, T0);

        verify(bridge).contextObserved(eq(RUN_ID), any(), eq(T0));
        verify(bridge).decisionObserved(eq(RUN_ID), any(), eq(T0));
    }
}
