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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ChatContextFactoryCanonicalSnapshotTest {

    @Test
    void defaultModeStillUsesLegacyContextAssembler() {
        Fixture fixture = new Fixture(false);

        ChatContext context = fixture.factory.build(
                new ChatRequest("s1", "u1", "hello", "api", "agent"),
                true,
                "0123456789abcdef0123456789abcdef"
        );

        verify(fixture.contextAssembler).assemble("s1", "api", "u1", "hello");
        verifyNoInteractions(
                fixture.contextSnapshotFactory,
                fixture.legacyContextViewAdapter
        );
        assertThat(context.assembled().observePrompt()).isEqualTo("legacy observe");
    }

    @Test
    void canonicalModeUsesSnapshotFactoryAndSkipsContextAssembler() {
        Fixture fixture = new Fixture(true);

        ChatContext context = fixture.factory.build(
                new ChatRequest("s1", "u1", "hello", "api", "agent"),
                true,
                "0123456789abcdef0123456789abcdef"
        );

        verifyNoInteractions(fixture.contextAssembler);
        verify(fixture.contextSnapshotFactory).create(any(ContextSnapshotRequest.class));
        verify(fixture.legacyContextViewAdapter).adapt(fixture.snapshot);
        assertThat(context.assembled().observePrompt()).isEqualTo("canonical observe");
        assertThat(context.contextInjection().observePrompt()).isEqualTo("canonical observe");
    }

    @Test
    void canonicalModeReusesStoredSnapshotWithoutRebuildingPromptOrSnapshot() {
        ContextSnapshot stored = snapshot();
        Fixture fixture = new Fixture(true, stored, contextReadyRun(stored));

        ChatContext context = fixture.factory.build(
                new ChatRequest("s1", "u1", "hello", "api", "agent"),
                true,
                "0123456789abcdef0123456789abcdef"
        );

        assertThat(context.contextSnapshot()).isSameAs(stored);
        assertThat(context.effectiveUserMessage()).isEqualTo("hello");
        assertThat(context.systemPrompt()).isEqualTo("system");
        verifyNoInteractions(fixture.contextSnapshotFactory, fixture.requestFactory);
        verify(fixture.soulPromptService, never()).buildSystemPrompt(any(), any(), any());
        verify(fixture.authService, never()).resolveRoleByUserId(any());
    }

    @Test
    void canonicalModeRejectsProviderDriftWhenReusingStoredSnapshot() {
        ContextSnapshot stored = snapshot("other-provider", "model");
        Fixture fixture = new Fixture(true, stored, contextReadyRun(stored));

        assertThatThrownBy(() -> fixture.factory.build(
                new ChatRequest("s1", "u1", "hello", "api", "agent"),
                true,
                "0123456789abcdef0123456789abcdef"
        ))
                .isInstanceOf(CanonicalRunContextException.class)
                .extracting(error -> ((CanonicalRunContextException) error).code())
                .isEqualTo(CanonicalRunContextException.Code.CANONICAL_PROVIDER_MISMATCH);
        verifyNoInteractions(fixture.contextSnapshotFactory, fixture.requestFactory);
    }

    @Test
    void canonicalModeRejectsModelDriftWhenReusingStoredSnapshot() {
        ContextSnapshot stored = snapshot("provider", "other-model");
        Fixture fixture = new Fixture(true, stored, contextReadyRun(stored));

        assertThatThrownBy(() -> fixture.factory.build(
                new ChatRequest("s1", "u1", "hello", "api", "agent"),
                true,
                "0123456789abcdef0123456789abcdef"
        ))
                .isInstanceOf(CanonicalRunContextException.class)
                .extracting(error -> ((CanonicalRunContextException) error).code())
                .isEqualTo(CanonicalRunContextException.Code.CANONICAL_PROVIDER_MISMATCH);
    }

    @Test
    void canonicalModeAllowsReuseWhenFrozenProviderFieldsAreBlank() {
        ContextSnapshot stored = snapshot("", "");
        Fixture fixture = new Fixture(true, stored, contextReadyRun(stored));

        ChatContext context = fixture.factory.build(
                new ChatRequest("s1", "u1", "hello", "api", "agent"),
                true,
                "0123456789abcdef0123456789abcdef"
        );

        assertThat(context.contextSnapshot()).isSameAs(stored);
        verifyNoInteractions(fixture.contextSnapshotFactory, fixture.requestFactory);
    }

    private static final class Fixture {
        private final AiProviderService aiProviderService = mock(AiProviderService.class);
        private final SoulPromptService soulPromptService = mock(SoulPromptService.class);
        private final AgentSessionService agentSessionService = mock(AgentSessionService.class);
        private final AuthService authService = mock(AuthService.class);
        private final SkillService skillService = mock(SkillService.class);
        private final SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
        private final ChatRoutingStateService chatRoutingStateService = mock(ChatRoutingStateService.class);
        private final ChatRoutingPolicyService chatRoutingPolicyService = mock(ChatRoutingPolicyService.class);
        private final AgentDecisionService agentDecisionService = mock(AgentDecisionService.class);
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
        private final ContextSnapshot snapshot;
        private final RunState runState;
        private final ContextSnapshotRequest snapshotRequest = snapshotRequest();
        private final ChatContextFactory factory;

        @SuppressWarnings("unchecked")
        private Fixture(boolean canonicalMode) {
            this(canonicalMode, snapshot(), null);
        }

        @SuppressWarnings("unchecked")
        private Fixture(
                boolean canonicalMode,
                ContextSnapshot snapshot,
                RunState runState
        ) {
            this.snapshot = snapshot;
            this.runState = runState == null
                    ? ChatContextFactoryCanonicalSnapshotTest.runState()
                    : runState;
            AgentSession session = new AgentSession();
            session.setId(1L);
            session.setSessionKey("s1");
            session.setUserId("u1");
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
                    "s1",
                    "api",
                    "u1",
                    "hello",
                    "legacy events",
                    "legacy memory",
                    "legacy observe"
            );
            LegacyContextView canonicalView = new LegacyContextView(
                    new AssembledContext(
                            "s1",
                            "api",
                            "u1",
                            "hello",
                            "canonical events",
                            "canonical memory",
                            "canonical observe"
                    ),
                    new ContextInjection("canonical observe", "", "", Map.of())
            );
            when(agentSessionService.getOrCreate("s1", "api", "u1")).thenReturn(session);
            when(authService.resolveRoleByUserId("u1")).thenReturn("USER");
            when(skillService.resolveAllowedToolPacks("api", "u1")).thenReturn(allowedToolPacks);
            when(agentDecisionService.decide(any(AgentDecisionRequest.class))).thenReturn(decision);
            when(chatRoutingStateService.resolveDefaultMode("simplified")).thenReturn("simplified");
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
            when(soulPromptService.buildSystemPrompt("api", "u1", List.of()))
                    .thenReturn("system");
            when(contextAssembler.assemble("s1", "api", "u1", "hello"))
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
            when(runStateRepository.requireByRunId("0123456789abcdef0123456789abcdef"))
                    .thenReturn(this.runState);
            when(requestFactory.create(
                    eq(this.runState),
                    eq("hello"),
                    eq("system"),
                    eq(List.of("web")),
                    any()
            )).thenReturn(snapshotRequest);
            when(projector.project(
                    eq("0123456789abcdef0123456789abcdef"),
                    eq(snapshot),
                    any()
            )).thenReturn(contextReadyRun(snapshot));

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

    private static ContextSnapshot snapshot() {
        return snapshot("provider", null);
    }

    private static ContextSnapshot snapshot(String providerId, String model) {
        Map<String, String> providerSnapshot = model == null
                ? Map.of("providerId", providerId)
                : Map.of("providerId", providerId, "model", model);
        return new ContextSnapshot(
                "0123456789abcdef0123456789abcdef",
                "s1",
                "u1",
                "api",
                "u1",
                "USER",
                "hello",
                "hello",
                "system",
                "project",
                List.of("short"),
                List.of("semantic"),
                List.of("rule"),
                List.of("web"),
                providerSnapshot,
                Map.of("schema", "test"),
                frame(),
                Instant.parse("2026-06-24T00:00:00Z"),
                "snapshot-hash"
        );
    }

    private static ContextSnapshotRequest snapshotRequest() {
        return new ContextSnapshotRequest(
                "0123456789abcdef0123456789abcdef",
                "s1",
                "u1",
                "api",
                "u1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "s1",
                        "u1"
                ),
                "USER",
                "hello",
                "hello",
                "system",
                List.of("web"),
                Map.of("providerId", "provider")
        );
    }

    private static RunState runState() {
        Instant now = Instant.parse("2026-06-24T00:00:00Z");
        return new RunState(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef0123456789abcdef",
                0,
                RunStatus.CREATED,
                "s1",
                "api",
                "u1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "s1",
                        "u1"
                ),
                "USER",
                "hello",
                "agent",
                now,
                null,
                now,
                null,
                now.plusSeconds(300),
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
        Instant now = Instant.parse("2026-06-24T00:00:00Z");
        return new RunState(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef0123456789abcdef",
                1,
                RunStatus.CONTEXT_READY,
                "s1",
                "api",
                "u1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "s1",
                        "u1"
                ),
                "USER",
                "hello",
                "agent",
                now,
                null,
                now.plusSeconds(1),
                null,
                now.plusSeconds(300),
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
                "0123456789abcdef0123456789abcdef",
                MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "s1",
                        "u1"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z"),
                "frame-hash"
        );
    }
}
