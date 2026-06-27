package com.springclaw.service.chat.async;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.bridge.DefaultRunLifecycleBridge;
import com.springclaw.runtime.bridge.LegacyExecutionDecisionAdapter;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.bridge.LegacyRunContextAdapter;
import com.springclaw.runtime.bridge.LegacyRunResultAdapter;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncChatResultStoreProjectionTest {

    private static final String RUN_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Instant T0 = Instant.parse("2026-06-22T00:00:00Z");

    @Test
    void markQueuedProjectsAcceptedNonterminalRun() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        AsyncChatResultStore resultStore = newStore(store);

        AsyncChatResultPayload payload = resultStore.markQueued(message());

        assertThat(payload.status()).isEqualTo("QUEUED");
        assertThat(payload.requestId()).isEqualTo(RUN_ID);
    }

    @Test
    void markCompletedProjectsDegradedCanonicalRunAsLegacyCompleted() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunLifecycleObserver observer = advanceToDegraded(store);

        AsyncChatResultStore resultStore = newStore(store);
        AsyncChatResultPayload payload = resultStore.markCompleted(message(), "answer", "model");

        assertThat(payload.status()).isEqualTo("COMPLETED");
        assertThat(payload.answer()).isEqualTo("answer");
    }

    @Test
    void markFailedProjectsFailedCanonicalRun() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        coordinator.failed(RUN_ID,
                new RunState.Failure("LEGACY_EXECUTION_FAILED", "boom", false), T0);

        AsyncChatResultStore resultStore = newStore(store);
        AsyncChatResultPayload payload = resultStore.markFailed(message(), "boom");

        assertThat(payload.status()).isEqualTo("FAILED");
        assertThat(payload.errorMessage()).isEqualTo("boom");
    }

    @Test
    void markFailedDoesNotOverwriteWhenCanonicalRunIsNotFailed() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        advanceToDegraded(store);
        AsyncChatResultStore resultStore = newStore(store);
        resultStore.markCompleted(message(), "answer", "model");

        AsyncChatResultPayload payload = resultStore.markFailed(message(), "late notification failure");

        assertThat(payload.status()).isEqualTo("COMPLETED");
        assertThat(payload.answer()).isEqualTo("answer");
    }

    @Test
    void markCompletedRejectsNonTerminalCanonicalStatus() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        AsyncChatResultStore resultStore = newStore(store);

        assertThatThrownBy(() -> resultStore.markCompleted(message(), "answer", "model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not one of the required terminal states");
    }

    @Test
    void markQueuedRejectsMissingCanonicalRun() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        AsyncChatResultStore resultStore = newStore(store);

        assertThatThrownBy(() -> resultStore.markQueued(message()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical run not found");
    }

    private static RunLifecycleObserver advanceToDegraded(InMemoryRunLifecycleStore store) {
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        RunLifecycleObserver observer = new RunLifecycleObserver(
                new DefaultRunLifecycleBridge(coordinator),
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                false
        );
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "plan", "action", "reflect", true);
        observer.contextAndDecisionObserved(context, T0.plusSeconds(1));
        observer.executionStarted(context, "simplified", T0.plusSeconds(2));
        observer.resultReturned(context, result, "legacy answer", T0.plusSeconds(3));
        return observer;
    }

    private static void acceptRun(RunCoordinator coordinator) {
        coordinator.accept(new RunAcceptance(
                RUN_ID, "session", "api", "user",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session",
                        "user"
                ),
                "USER", "hello", "agent",
                T0, T0.plus(Duration.ofMinutes(30))
        ));
    }

    @SuppressWarnings("unchecked")
    private static AsyncChatResultStore newStore(InMemoryRunLifecycleStore store) {
        ObjectProvider<org.redisson.api.RedissonClient> redissonProvider = mock(ObjectProvider.class);
        when(redissonProvider.getIfAvailable()).thenReturn(null);
        return new AsyncChatResultStore(
                redissonProvider,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                store,
                "results:",
                24
        );
    }

    private static AsyncChatRequestMessage message() {
        return new AsyncChatRequestMessage(
                RUN_ID, "session", "user", "hello", "api", 100L, "agent");
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
