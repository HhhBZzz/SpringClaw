package com.springclaw.architecture;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.CanonicalContextReadyProjector;
import com.springclaw.runtime.bridge.LegacyContextView;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.impl.ChatContextFactory;
import com.springclaw.service.chat.impl.ChatRoutingPolicyService;
import com.springclaw.service.chat.impl.ChatRoutingStateService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.context.ContextInjection;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalRetrievalBoundaryTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-25T00:00:00Z");

    @Test
    void canonicalModeDoesNotUseLegacyContextAssembler() {
        Fixture fixture = new Fixture(true);

        fixture.factory.build(
                new ChatRequest("session-1", "user-1", "hello", "api", "agent", null),
                true,
                RUN_ID
        );

        verifyNoInteractions(fixture.contextAssembler);
        verify(fixture.contextSnapshotFactory).create(any());
        verify(fixture.projector).project(eq(RUN_ID), eq(fixture.snapshot), any());
    }

    @Test
    void rollbackModeStillUsesLegacyContextAssembler() {
        Fixture fixture = new Fixture(false);

        fixture.factory.build(
                new ChatRequest("session-1", "user-1", "hello", "api", "agent", null),
                true,
                RUN_ID
        );

        verify(fixture.contextAssembler)
                .assemble("session-1", "api", "user-1", "hello");
        verifyNoInteractions(
                fixture.contextSnapshotFactory,
                fixture.projector,
                fixture.runStateRepository
        );
    }

    private static final class Fixture {
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
        private final ContextSnapshotFactory contextSnapshotFactory =
                mock(ContextSnapshotFactory.class);
        private final LegacyContextViewAdapter legacyContextViewAdapter =
                mock(LegacyContextViewAdapter.class);
        private final RunStateRepository runStateRepository =
                mock(RunStateRepository.class);
        private final RunStateContextSnapshotRequestFactory requestFactory =
                mock(RunStateContextSnapshotRequestFactory.class);
        private final CanonicalContextReadyProjector projector =
                mock(CanonicalContextReadyProjector.class);
        private final ContextSnapshot snapshot = snapshot();
        private final ChatContextFactory factory;

        @SuppressWarnings("unchecked")
        private Fixture(boolean canonicalMode) {
            AgentSession session = new AgentSession();
            session.setSessionKey("session-1");
            session.setUserId("user-1");
            session.setChannel("api");
            Set<String> allowedToolPacks = Set.of("web");
            AgentDecision decision = new AgentDecision(
                    "general",
                    "basic_model",
                    List.of("web"),
                    "read",
                    false,
                    "test"
            );
            AssembledContext legacy = new AssembledContext(
                    "session-1",
                    "api",
                    "user-1",
                    "hello",
                    "legacy events",
                    "legacy memory",
                    "legacy observe"
            );
            LegacyContextView view = new LegacyContextView(
                    new AssembledContext(
                            "session-1",
                            "api",
                            "user-1",
                            "hello",
                            "canonical events",
                            "canonical memory",
                            "canonical observe"
                    ),
                    new ContextInjection("canonical observe", "", "", Map.of())
            );

            when(agentSessionService.getOrCreate("session-1", "api", "user-1"))
                    .thenReturn(session);
            when(authService.resolveRoleByUserId("user-1")).thenReturn("USER");
            when(skillService.resolveAllowedToolPacks("api", "user-1"))
                    .thenReturn(allowedToolPacks);
            when(agentDecisionService.decide(any(AgentDecisionRequest.class)))
                    .thenReturn(decision);
            when(chatRoutingStateService.resolveDefaultMode("simplified"))
                    .thenReturn("simplified");
            when(chatRoutingStateService.resolveAutoUpgrade(true)).thenReturn(true);
            when(chatRoutingPolicyService.decide(
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
                    "test",
                    "agent",
                    "general"
            ));
            when(skillRegistryService.matchAgentVisibleDefinitions(
                    "hello",
                    allowedToolPacks,
                    2
            )).thenReturn(List.of());
            when(soulPromptService.buildSystemPrompt("api", "user-1", List.of()))
                    .thenReturn("system");
            when(aiProviderService.activeClient()).thenReturn(
                    new AiProviderService.ActiveChatClient(
                            "provider",
                            "model",
                            "https://example.test",
                            mock(ChatClient.class),
                            true,
                            ""
                    )
            );
            when(contextAssembler.assemble("session-1", "api", "user-1", "hello"))
                    .thenReturn(legacy);
            when(runStateRepository.requireByRunId(RUN_ID)).thenReturn(runState());
            when(requestFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(snapshotRequest());
            when(contextSnapshotFactory.create(any())).thenReturn(snapshot);
            when(projector.project(eq(RUN_ID), eq(snapshot), any()))
                    .thenReturn(contextReadyRun(snapshot));
            when(legacyContextViewAdapter.adapt(snapshot)).thenReturn(view);

            ObjectProvider<ContextSnapshotFactory> snapshotProvider =
                    (ObjectProvider<ContextSnapshotFactory>) mock(ObjectProvider.class);
            ObjectProvider<LegacyContextViewAdapter> adapterProvider =
                    (ObjectProvider<LegacyContextViewAdapter>) mock(ObjectProvider.class);
            ObjectProvider<com.springclaw.runtime.bridge.AcceptedRunContextResolver>
                    acceptedRunContextResolverProvider =
                    (ObjectProvider<com.springclaw.runtime.bridge.AcceptedRunContextResolver>)
                            mock(ObjectProvider.class);
            ObjectProvider<RunStateContextSnapshotRequestFactory> requestFactoryProvider =
                    (ObjectProvider<RunStateContextSnapshotRequestFactory>) mock(ObjectProvider.class);
            ObjectProvider<com.springclaw.runtime.bridge.CanonicalContextSnapshotResolver>
                    canonicalContextSnapshotResolverProvider =
                    (ObjectProvider<com.springclaw.runtime.bridge.CanonicalContextSnapshotResolver>)
                            mock(ObjectProvider.class);
            when(snapshotProvider.getIfAvailable()).thenReturn(contextSnapshotFactory);
            when(adapterProvider.getIfAvailable()).thenReturn(legacyContextViewAdapter);
            when(acceptedRunContextResolverProvider.getIfAvailable()).thenReturn(
                    new com.springclaw.runtime.bridge.AcceptedRunContextResolver(
                            runStateRepository
                    )
            );
            when(requestFactoryProvider.getIfAvailable()).thenReturn(requestFactory);
            when(canonicalContextSnapshotResolverProvider.getIfAvailable()).thenReturn(
                    new com.springclaw.runtime.bridge.CanonicalContextSnapshotResolver(
                            projector,
                            runStateRepository
                    )
            );

            this.factory = new ChatContextFactory(
                    aiProviderService,
                    soulPromptService,
                    agentSessionService,
                    authService,
                    skillService,
                    skillRegistryService,
                    contextAssembler,
                    chatRoutingStateService,
                    chatRoutingPolicyService,
                    agentDecisionService,
                    snapshotProvider,
                    adapterProvider,
                    acceptedRunContextResolverProvider,
                    requestFactoryProvider,
                    canonicalContextSnapshotResolverProvider,
                    canonicalMode,
                    "simplified",
                    true
            );
        }
    }

    private static RunState runState() {
        return new RunState(
                RUN_ID,
                RUN_ID,
                0,
                RunStatus.CREATED,
                "session-1",
                "api",
                "user-1",
                claim(),
                "USER",
                "hello",
                "agent",
                T0,
                null,
                T0,
                null,
                T0.plusSeconds(300),
                null,
                null,
                "",
                1,
                "",
                List.of(),
                null,
                null,
                Map.of(),
                null
        );
    }

    private static RunState contextReadyRun(ContextSnapshot snapshot) {
        return new RunState(
                RUN_ID,
                RUN_ID,
                1,
                RunStatus.CONTEXT_READY,
                "session-1",
                "api",
                "user-1",
                claim(),
                "USER",
                "hello",
                "agent",
                T0,
                null,
                T0.plusSeconds(1),
                null,
                T0.plusSeconds(300),
                snapshot,
                null,
                "",
                1,
                "",
                List.of(),
                null,
                null,
                Map.of(),
                null
        );
    }

    private static com.springclaw.runtime.contract.ContextSnapshotRequest snapshotRequest() {
        return new com.springclaw.runtime.contract.ContextSnapshotRequest(
                RUN_ID,
                "session-1",
                "user-1",
                "api",
                "user-1",
                claim(),
                "USER",
                "hello",
                "hello",
                "system",
                List.of("web"),
                Map.of("providerId", "provider")
        );
    }

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                RUN_ID,
                "session-1",
                "user-1",
                "api",
                "user-1",
                "USER",
                "hello",
                "hello",
                "system",
                "project",
                List.of(),
                List.of(),
                List.of(),
                List.of("web"),
                Map.of("providerId", "provider"),
                Map.of("schema", "test"),
                new MemoryFrame(
                        RUN_ID,
                        MemoryScope.from(claim()),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("source", "test"),
                        List.of(),
                        T0,
                        "frame-hash"
                ),
                T0,
                "snapshot-hash"
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
