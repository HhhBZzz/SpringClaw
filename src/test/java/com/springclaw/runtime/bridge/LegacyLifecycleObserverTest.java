package com.springclaw.runtime.bridge;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyLifecycleObserverTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-22T00:00:00Z");

    @Test
    void observesLegacyFactsInCanonicalOrderWithoutClaimingCompletion() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        coordinator.accept(new RunAcceptance(
                RUN_ID, "session-1", "api", "user-1",
                claim(), "USER", "original",
                "agent", T0, T0.plusSeconds(300)
        ));
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                new DefaultLegacyRuntimeBridge(coordinator),
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                false
        );
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "plan", "action", "reflect", true
        );

        observer.contextAndDecisionObserved(context, T0.plusSeconds(1));
        observer.executionStarted(context, "agent-runtime", T0.plusSeconds(2));
        observer.resultReturned(
                context, result, "legacy answer", T0.plusSeconds(3)
        );

        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.DEGRADED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.VERIFICATION_COMPLETED,
                        RunEventType.RUN_DEGRADED
                );
    }

    @Test
    void confirmationSuspendsTheSameRunningRun() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        coordinator.accept(new RunAcceptance(
                RUN_ID, "session-1", "api", "user-1",
                claim(), "USER", "original",
                "agent", T0, T0.plusSeconds(300)
        ));
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                new DefaultLegacyRuntimeBridge(coordinator),
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                false
        );

        observer.contextAndDecisionObserved(context(), T0.plusSeconds(1));
        observer.executionStarted(context(), "agent-runtime", T0.plusSeconds(2));
        observer.confirmationRequired(RUN_ID, "proposal-1", T0.plusSeconds(3));

        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.WAITING_CONFIRMATION);
    }

    private static ChatContext context() {
        AgentSession session = new AgentSession();
        session.setSessionKey("session-1");
        session.setUserId("user-1");
        session.setChannel("api");
        AssembledContext assembled = new AssembledContext(
                "session-1", "api", "user-1", "effective",
                "short-term", "semantic", "observe-prompt"
        );
        return new ChatContext(
                session, "api", "user-1", "USER", "original", "effective",
                RUN_ID, "system", assembled,
                new AiProviderService.ActiveChatClient(
                        "provider", "model", "https://example.test",
                        null, true, ""
                ),
                "simplified", "legacy routing", "agent", "web_research",
                new AgentDecision(
                        "web_research", "agent_tools", List.of("web"),
                        "read", false, "legacy decision"
                ),
                new ContextInjection(
                        "observe-prompt", "", "",
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
