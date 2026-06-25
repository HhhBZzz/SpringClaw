package com.springclaw.runtime.bridge;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LegacyLifecycleObserverCanonicalModeTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void canonicalModeSkipsContextObservationButKeepsDecisionObservation() {
        LegacyRuntimeBridge bridge = mock(LegacyRuntimeBridge.class);
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                bridge,
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                true
        );
        ChatContext context = context(RUN_ID);

        observer.contextAndDecisionObserved(context, T0);

        verify(bridge, never()).contextObserved(any(), any(), any());
        verify(bridge).decisionObserved(eq(RUN_ID), any(), eq(T0));
    }

    @Test
    void canonicalModeDoesNotEmitASecondContextReadyEvent() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        coordinator.accept(new RunAcceptance(
                RUN_ID,
                "session-1",
                "api",
                "user-1",
                claim(),
                "USER",
                "original",
                "agent",
                T0,
                T0.plusSeconds(300)
        ));
        ChatContext context = context(RUN_ID);
        coordinator.contextReady(
                RUN_ID,
                new LegacyRunContextAdapter().adapt(context, T0.plusSeconds(1)),
                T0.plusSeconds(1)
        );
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                new DefaultLegacyRuntimeBridge(coordinator),
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                true
        );

        observer.contextAndDecisionObserved(context, T0.plusSeconds(2));

        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE
                );
    }

    @Test
    void legacyModeKeepsContextAndDecisionObservation() {
        LegacyRuntimeBridge bridge = mock(LegacyRuntimeBridge.class);
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                bridge,
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                false
        );
        ChatContext context = context(RUN_ID);

        observer.contextAndDecisionObserved(context, T0);

        verify(bridge).contextObserved(eq(RUN_ID), any(), eq(T0));
        verify(bridge).decisionObserved(eq(RUN_ID), any(), eq(T0));
    }

    private static ChatContext context(String runId) {
        AgentSession session = new AgentSession();
        session.setSessionKey("session-1");
        session.setUserId("user-1");
        session.setChannel("api");
        AssembledContext assembled = new AssembledContext(
                "session-1",
                "api",
                "user-1",
                "effective",
                "short-term",
                "semantic",
                "observe-prompt"
        );
        return new ChatContext(
                session,
                "api",
                "user-1",
                "USER",
                "original",
                "effective",
                runId,
                "system",
                assembled,
                new AiProviderService.ActiveChatClient(
                        "provider",
                        "model",
                        "https://example.test",
                        null,
                        true,
                        ""
                ),
                "simplified",
                "legacy routing",
                "agent",
                "web_research",
                new AgentDecision(
                        "web_research",
                        "agent_tools",
                        List.of("web"),
                        "read",
                        false,
                        "legacy decision"
                ),
                new ContextInjection(
                        "observe-prompt",
                        "",
                        "",
                        Map.of("contextSummary", assembled.sourceSummary())
                )
        );
    }

    private static SessionAccessClaim claim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "user-1"
        );
    }
}
