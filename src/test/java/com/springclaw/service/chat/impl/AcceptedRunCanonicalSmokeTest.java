package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.CanonicalContextReadyProjector;
import com.springclaw.runtime.bridge.DefaultRunLifecycleBridge;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunExecutionDecisionProjector;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.bridge.RollbackRunContextAdapter;
import com.springclaw.runtime.bridge.RunResultProjector;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AcceptedRunCanonicalSmokeTest {

    private static final String RUN_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Instant T0 = Instant.parse("2026-06-25T01:00:00Z");

    @Test
    void acceptedRunUsesCanonicalContextReadyAndReachesTerminalState() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        SessionAccessClaim claim = claim();
        coordinator.accept(new RunAcceptance(
                RUN_ID,
                "session-1",
                "api",
                "user-1",
                claim,
                "USER",
                "hello",
                "agent",
                T0,
                T0.plusSeconds(300)
        ));
        MemoryCoordinator memoryCoordinator = mock(MemoryCoordinator.class);
        when(memoryCoordinator.retrieve(any(MemoryFrameRequest.class)))
                .thenReturn(memoryResult(claim));
        ChatContextFactory factory = factory(
                store,
                coordinator,
                memoryCoordinator
        );
        RunLifecycleObserver observer = new RunLifecycleObserver(
                new DefaultRunLifecycleBridge(coordinator),
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                true
        );

        ChatContext context = factory.build(
                new ChatRequest("session-1", "user-1", "hello", "api", "agent"),
                true,
                RUN_ID
        );
        observer.contextAndDecisionObserved(context, T0.plusSeconds(2));
        observer.executionStarted(context, "simplified", T0.plusSeconds(3));
        observer.resultReturned(
                context,
                new ChatExecutionResult("observe", "plan", "action", "answer", true),
                "answer",
                T0.plusSeconds(4)
        );

        RunState state = store.requireByRunId(RUN_ID);
        assertThat(state.status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(context.session().getSessionKey()).isEqualTo("session-1");
        assertThat(context.userId()).isEqualTo("user-1");
        assertThat(context.assembled().observePrompt())
                .contains("short-term turn")
                .contains("semantic fact")
                .contains("procedural rule")
                .contains("project memory");
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
        ArgumentCaptor<MemoryFrameRequest> requestCaptor =
                ArgumentCaptor.forClass(MemoryFrameRequest.class);
        verify(memoryCoordinator).retrieve(requestCaptor.capture());
        assertThat(requestCaptor.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(requestCaptor.getValue().scope()).isEqualTo(MemoryScope.from(claim));
        verifyNoInteractions(factoryDeps.contextAssembler);
    }

    private static FixtureDeps factoryDeps;

    @SuppressWarnings("unchecked")
    private static ChatContextFactory factory(
            InMemoryRunLifecycleStore store,
            RunCoordinator coordinator,
            MemoryCoordinator memoryCoordinator
    ) {
        FixtureDeps deps = new FixtureDeps();
        factoryDeps = deps;
        AgentSession session = new AgentSession();
        session.setSessionKey("session-1");
        session.setChannel("api");
        session.setUserId("user-1");
        Set<String> allowedToolPacks = Set.of("web");

        when(deps.agentSessionService.getOrCreate("session-1", "api", "user-1"))
                .thenReturn(session);
        when(deps.authService.resolveRoleByUserId("user-1")).thenReturn("USER");
        when(deps.skillService.resolveAllowedToolPacks("api", "user-1"))
                .thenReturn(allowedToolPacks);
        when(deps.agentDecisionService.decide(any(AgentDecisionRequest.class)))
                .thenReturn(new AgentDecision(
                        "general",
                        "basic_model",
                        List.of("web"),
                        "read",
                        false,
                        "smoke"
                ));
        when(deps.chatRoutingStateService.resolveDefaultMode("simplified"))
                .thenReturn("simplified");
        when(deps.chatRoutingStateService.resolveAutoUpgrade(true)).thenReturn(true);
        when(deps.chatRoutingPolicyService.decide(
                eq("hello"),
                eq("USER"),
                eq("simplified"),
                eq(true),
                eq(allowedToolPacks),
                eq("agent")
        )).thenReturn(new ChatRoutingPolicyService.RoutingDecision(
                "hello",
                "simplified",
                false,
                false,
                "smoke",
                "agent",
                "general"
        ));
        when(deps.skillRegistryService.matchAgentVisibleDefinitions(
                "hello",
                allowedToolPacks,
                2
        )).thenReturn(List.of());
        when(deps.soulPromptService.buildSystemPrompt("api", "user-1", List.of()))
                .thenReturn("system prompt");
        when(deps.aiProviderService.activeClient()).thenReturn(
                new AiProviderService.ActiveChatClient(
                        "provider",
                        "model",
                        "https://example.test",
                        mock(ChatClient.class),
                        true,
                        ""
                )
        );

        ObjectProvider<ContextSnapshotFactory> snapshotProvider =
                (ObjectProvider<ContextSnapshotFactory>) mock(ObjectProvider.class);
        ObjectProvider<LegacyContextViewAdapter> adapterProvider =
                (ObjectProvider<LegacyContextViewAdapter>) mock(ObjectProvider.class);
        ObjectProvider<com.springclaw.runtime.lifecycle.RunStateRepository> runStateProvider =
                (ObjectProvider<com.springclaw.runtime.lifecycle.RunStateRepository>) mock(ObjectProvider.class);
        ObjectProvider<RunStateContextSnapshotRequestFactory> requestFactoryProvider =
                (ObjectProvider<RunStateContextSnapshotRequestFactory>) mock(ObjectProvider.class);
        ObjectProvider<CanonicalContextReadyProjector> projectorProvider =
                (ObjectProvider<CanonicalContextReadyProjector>) mock(ObjectProvider.class);
        when(snapshotProvider.getIfAvailable()).thenReturn(new ContextSnapshotFactory(
                memoryCoordinator,
                Clock.fixed(T0.plusSeconds(1), ZoneOffset.UTC)
        ));
        when(adapterProvider.getIfAvailable()).thenReturn(new LegacyContextViewAdapter());
        when(runStateProvider.getIfAvailable()).thenReturn(store);
        when(requestFactoryProvider.getIfAvailable())
                .thenReturn(new RunStateContextSnapshotRequestFactory());
        when(projectorProvider.getIfAvailable())
                .thenReturn(new CanonicalContextReadyProjector(coordinator, store));

        return new ChatContextFactory(
                deps.aiProviderService,
                deps.soulPromptService,
                deps.agentSessionService,
                deps.authService,
                deps.skillService,
                deps.skillRegistryService,
                deps.contextAssembler,
                deps.chatRoutingStateService,
                deps.chatRoutingPolicyService,
                deps.agentDecisionService,
                snapshotProvider,
                adapterProvider,
                runStateProvider,
                requestFactoryProvider,
                projectorProvider,
                true,
                "simplified",
                true
        );
    }

    private static MemoryFrameResult memoryResult(SessionAccessClaim claim) {
        MemoryScope scope = MemoryScope.from(claim);
        MemoryFrame frame = new MemoryFrame(
                RUN_ID,
                scope,
                List.of(),
                List.of(item("short-1", MemoryFrameLayer.SHORT_TERM, "short-term turn")),
                List.of(),
                List.of(item("semantic-1", MemoryFrameLayer.SEMANTIC_FACT, "semantic fact")),
                List.of(item("rule-1", MemoryFrameLayer.PROCEDURAL_RULE, "procedural rule")),
                List.of(item("project-1", MemoryFrameLayer.PROJECT, "project memory")),
                Map.of("source", "smoke"),
                List.of(),
                T0.plusSeconds(1),
                "frame-hash-smoke"
        );
        return new MemoryFrameResult(
                frame,
                new MemoryRetrievalTrace(
                        RUN_ID,
                        scope,
                        frame.frameHash(),
                        Map.of("smoke", 4),
                        Map.of("included", 4),
                        Map.of(),
                        List.of(),
                        T0.plusSeconds(1)
                )
        );
    }

    private static MemoryFrameItem item(
            String sourceId,
            MemoryFrameLayer layer,
            String content
    ) {
        return new MemoryFrameItem(
                sourceId,
                layer == MemoryFrameLayer.PROJECT
                        ? MemoryFrameSourceKind.PROJECT_MARKDOWN
                        : MemoryFrameSourceKind.MEMORY_RECORD,
                layer,
                sourceId,
                sourceId + "-v1",
                switch (layer) {
                    case PROCEDURAL_RULE -> MemoryType.PROCEDURAL;
                    case SEMANTIC_FACT -> MemoryType.SEMANTIC;
                    default -> MemoryType.EPISODIC;
                },
                MemoryScopeType.PERSONAL_SESSION,
                "session-1",
                content,
                sourceId + "-hash",
                List.of("evidence:" + sourceId),
                0.8,
                0.9,
                0.7,
                1,
                T0.plusSeconds(1)
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

    private static final class FixtureDeps {
        private final AiProviderService aiProviderService = mock(AiProviderService.class);
        private final SoulPromptService soulPromptService = mock(SoulPromptService.class);
        private final AgentSessionService agentSessionService =
                mock(AgentSessionService.class);
        private final AuthService authService = mock(AuthService.class);
        private final SkillService skillService = mock(SkillService.class);
        private final SkillRegistryService skillRegistryService =
                mock(SkillRegistryService.class);
        private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
        private final ChatRoutingStateService chatRoutingStateService =
                mock(ChatRoutingStateService.class);
        private final ChatRoutingPolicyService chatRoutingPolicyService =
                mock(ChatRoutingPolicyService.class);
        private final AgentDecisionService agentDecisionService =
                mock(AgentDecisionService.class);
    }
}
