package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.CanonicalContextReadyProjector;
import com.springclaw.runtime.bridge.CanonicalRunContextException;
import com.springclaw.runtime.bridge.LegacyContextView;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.runtime.contract.ContextSnapshotRequest;
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
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.context.ContextInjection;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatContextFactoryCanonicalOwnershipTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void canonicalDefaultRejectsForgedRequestBeforeAnyDownstreamWork() {
        Fixture fixture = new Fixture(true);
        when(fixture.runStateRepository.requireByRunId(RUN_ID))
                .thenReturn(sharedRunState());

        assertThatThrownBy(() -> fixture.factory.build(
                new ChatRequest("forged-session", "mallory", "hello", "api", "agent", null),
                true,
                RUN_ID
        ))
                .isInstanceOf(CanonicalRunContextException.class)
                .extracting(error -> ((CanonicalRunContextException) error).code())
                .isEqualTo(CanonicalRunContextException.Code.ACCEPTED_REQUEST_MISMATCH);

        verify(fixture.runStateRepository).requireByRunId(RUN_ID);
        verify(fixture.agentSessionService, never()).getOrCreate(any(), any(), any());
        verifyNoInteractions(
                fixture.authService,
                fixture.skillService,
                fixture.chatRoutingPolicyService,
                fixture.soulPromptService,
                fixture.aiProviderService,
                fixture.contextSnapshotFactory,
                fixture.contextAssembler
        );
    }

    @Test
    void canonicalDefaultBuildsContextFromAcceptedSharedIdentityAndFrozenRole() {
        Fixture fixture = new Fixture(true);
        RunState accepted = sharedRunState();
        when(fixture.runStateRepository.requireByRunId(RUN_ID)).thenReturn(accepted);
        when(fixture.projector.project(eq(RUN_ID), eq(fixture.snapshot), any()))
                .thenReturn(sharedContextReady(fixture.snapshot));

        ChatContext context = fixture.factory.build(
                new ChatRequest("group-1", "ou-1", "hello", "feishu", "agent", null),
                true,
                RUN_ID
        );

        verify(fixture.agentSessionService).getOrCreate("group-1", "feishu", "ou-1");
        verifyNoInteractions(fixture.authService);
        verifyNoInteractions(fixture.contextAssembler);
        assertThat(context.channel()).isEqualTo("feishu");
        assertThat(context.userId()).isEqualTo("ou-1");
        assertThat(context.roleCode()).isEqualTo("MEMBER");
        assertThat(context.userMessage()).isEqualTo("hello");
        assertThat(context.requestId()).isEqualTo(RUN_ID);
        assertThat(context.contextSnapshot()).isSameAs(fixture.snapshot);
    }

    @Test
    void explicitLegacyRollbackUsesContextAssembler() {
        Fixture fixture = new Fixture(false);

        ChatContext context = fixture.factory.build(
                new ChatRequest("session-1", "alice", "hello", "api", "agent", null),
                true,
                RUN_ID
        );

        verify(fixture.contextAssembler).assemble("session-1", "api", "alice", "hello");
        verifyNoInteractions(fixture.runStateRepository, fixture.contextSnapshotFactory);
        assertThat(context.assembled().observePrompt()).isEqualTo("legacy observe");
    }

    @Test
    void canonicalDefaultFailsWhenAcceptedRunIsMissing() {
        Fixture fixture = new Fixture(true);
        when(fixture.runStateRepository.requireByRunId(RUN_ID))
                .thenThrow(new IllegalStateException("run not found: " + RUN_ID));

        assertThatThrownBy(() -> fixture.factory.build(
                new ChatRequest("session-1", "alice", "hello", "api", "agent", null),
                true,
                RUN_ID
        )).hasMessageContaining("run not found");
    }

    private static final class Fixture {
        private final AiProviderService aiProviderService = mock(AiProviderService.class);
        private final SoulPromptService soulPromptService = mock(SoulPromptService.class);
        private final AgentSessionService agentSessionService = mock(AgentSessionService.class);
        private final AuthService authService = mock(AuthService.class);
        private final SkillService skillService = mock(SkillService.class);
        private final SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
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
        private final CanonicalContextReadyProjector projector =
                mock(CanonicalContextReadyProjector.class);
        private final ContextSnapshot snapshot = snapshot();
        private final ChatContextFactory factory;

        @SuppressWarnings("unchecked")
        private Fixture(boolean canonicalMode) {
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
                    "alice",
                    "hello",
                    "legacy events",
                    "legacy memory",
                    "legacy observe"
            );
            LegacyContextView canonicalView = new LegacyContextView(
                    new AssembledContext(
                            "group-1",
                            "feishu",
                            "ou-1",
                            "hello",
                            "canonical events",
                            "canonical memory",
                            "canonical observe"
                    ),
                    new ContextInjection("canonical observe", "", "", Map.of())
            );

            when(agentSessionService.getOrCreate(any(), any(), any()))
                    .thenAnswer(invocation -> {
                        AgentSession session = new AgentSession();
                        session.setId(1L);
                        session.setSessionKey(invocation.getArgument(0));
                        session.setChannel(invocation.getArgument(1));
                        session.setUserId(invocation.getArgument(2));
                        return session;
                    });
            when(authService.resolveRoleByUserId(any())).thenReturn("USER");
            when(skillService.resolveAllowedToolPacks(any(), any()))
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
            when(skillRegistryService.matchAgentVisibleDefinitions("hello", allowedToolPacks, 2))
                    .thenReturn(List.of());
            when(soulPromptService.buildSystemPrompt(any(), any(), eq(List.of())))
                    .thenReturn("system");
            when(contextAssembler.assemble("session-1", "api", "alice", "hello"))
                    .thenReturn(legacy);
            when(aiProviderService.activeClient()).thenReturn(new AiProviderService.ActiveChatClient(
                    "provider",
                    "model",
                    "https://example.test",
                    mock(ChatClient.class),
                    true,
                    ""
            ));
            when(contextSnapshotFactory.create(any(ContextSnapshotRequest.class)))
                    .thenReturn(snapshot);
            when(legacyContextViewAdapter.adapt(snapshot)).thenReturn(canonicalView);

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
            when(requestFactoryProvider.getIfAvailable())
                    .thenReturn(new RunStateContextSnapshotRequestFactory());
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

    private static RunState sharedRunState() {
        SessionAccessClaim claim = SessionAccessClaim.sharedVerified(
                "feishu",
                "group-1",
                "ou-1"
        );
        return new RunState(
                RUN_ID,
                RUN_ID,
                0,
                RunStatus.CREATED,
                claim.sessionKey(),
                claim.channel(),
                claim.acceptedUserId(),
                claim,
                "MEMBER",
                "hello",
                "agent",
                NOW,
                null,
                NOW,
                null,
                NOW.plusSeconds(300),
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

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                RUN_ID,
                "group-1",
                "ou-1",
                "feishu",
                "ou-1",
                "MEMBER",
                "hello",
                "hello",
                "system",
                "project",
                List.of("short"),
                List.of("semantic"),
                List.of("rule"),
                List.of("web"),
                Map.of("providerId", "provider"),
                Map.of("schema", "test"),
                frame(),
                NOW,
                "snapshot-hash"
        );
    }

    private static RunState sharedContextReady(ContextSnapshot snapshot) {
        SessionAccessClaim claim = SessionAccessClaim.sharedVerified(
                "feishu",
                "group-1",
                "ou-1"
        );
        return new RunState(
                RUN_ID,
                RUN_ID,
                1,
                RunStatus.CONTEXT_READY,
                claim.sessionKey(),
                claim.channel(),
                claim.acceptedUserId(),
                claim,
                "MEMBER",
                "hello",
                "agent",
                NOW,
                null,
                NOW.plusSeconds(1),
                null,
                NOW.plusSeconds(300),
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

    private static MemoryFrame frame() {
        return new MemoryFrame(
                RUN_ID,
                MemoryScope.from(SessionAccessClaim.sharedVerified(
                        "feishu",
                        "group-1",
                        "ou-1"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                NOW,
                "frame-hash"
        );
    }
}
