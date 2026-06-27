package com.springclaw.service.proposal;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.bridge.DefaultRunLifecycleBridge;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.bridge.RunExecutionDecisionProjector;
import com.springclaw.runtime.bridge.RollbackRunContextAdapter;
import com.springclaw.runtime.bridge.RunResultProjector;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolProposalLifecycleListenerTest {

    private static final String RUN_ID = "cccccccccccccccccccccccccccccccc";
    private static final Instant T0 = Instant.parse("2026-06-22T00:00:00Z");

    @Test
    void onProposalCreatedCallsConfirmationRequiredAfterCommit() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        RunLifecycleObserver observer = realObserver(coordinator);
        advanceToRunning(coordinator, observer);
        ToolProposalLifecycleListener listener = new ToolProposalLifecycleListener(observer);

        listener.onProposalCreated(new ToolProposalCreatedEvent("tip-1", RUN_ID));

        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.WAITING_CONFIRMATION);
    }

    @Test
    void onProposalCreatedSkipsBlankRunId() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        RunLifecycleObserver observer = realObserver(coordinator);
        ToolProposalLifecycleListener listener = new ToolProposalLifecycleListener(observer);

        listener.onProposalCreated(new ToolProposalCreatedEvent("tip-2", ""));

        assertThat(store.findByRunId("")).isEmpty();
    }

    @Test
    void onProposalRejectedCallsConfirmationRejectedAfterCommit() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        RunLifecycleObserver observer = realObserver(coordinator);
        advanceToRunning(coordinator, observer);
        coordinator.waitingConfirmation(RUN_ID, "tip-3", T0.plusSeconds(4));
        ToolProposalLifecycleListener listener = new ToolProposalLifecycleListener(observer);

        listener.onProposalRejected(new ToolProposalRejectedEvent("tip-3", RUN_ID, "user said no"));

        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void onProposalCreatedSwallowsProjectionFailure() {
        // Observer backed by a store with no accepted run: confirmationRequired
        // throws, but the listener must not propagate it.
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        RunLifecycleObserver observer = realObserver(coordinator);
        ToolProposalLifecycleListener listener = new ToolProposalLifecycleListener(observer);

        listener.onProposalCreated(new ToolProposalCreatedEvent("tip-4", "run-missing"));

        assertThat(store.findByRunId("run-missing")).isEmpty();
    }

    private static RunLifecycleObserver realObserver(RunCoordinator coordinator) {
        return new RunLifecycleObserver(
                new DefaultRunLifecycleBridge(coordinator),
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                false
        );
    }

    private static void advanceToRunning(RunCoordinator coordinator, RunLifecycleObserver observer) {
        coordinator.accept(new RunAcceptance(
                RUN_ID, "session", "api", "user",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session",
                        "user"
                ),
                "USER", "hello", "agent",
                T0, T0.plus(Duration.ofMinutes(30))));
        ChatContext context = context();
        observer.contextAndDecisionObserved(context, T0.plusSeconds(1));
        observer.executionStarted(context, "simplified", T0.plusSeconds(2));
    }

    private static ChatContext context() {
        AgentSession session = new AgentSession();
        session.setSessionKey("session");
        session.setUserId("user");
        session.setChannel("api");
        AssembledContext assembled = new AssembledContext(
                "session", "api", "user", "effective",
                "short-term", "semantic", "observe-prompt");
        return new ChatContext(
                session, "api", "user", "USER", "hello", "effective",
                RUN_ID, "system", assembled,
                new AiProviderService.ActiveChatClient(
                        "provider", "model", "https://example.test",
                        null, true, ""
                ),
                "simplified", "legacy routing", "agent", "general",
                new AgentDecision(
                        "general", "agent_tools", List.of("web"),
                        "read", false, "legacy decision"),
                new ContextInjection(
                        "observe-prompt", "", "",
                        Map.of("contextSummary", assembled.sourceSummary()))
        );
    }
}
