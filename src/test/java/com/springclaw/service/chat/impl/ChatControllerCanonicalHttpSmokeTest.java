package com.springclaw.service.chat.impl;

import com.springclaw.controller.ChatController;
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
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageProducer;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerCanonicalHttpSmokeTest {

    private static final String RUN_ID = "dddddddddddddddddddddddddddddddd";
    private static final Instant T0 = Instant.parse("2026-06-25T02:00:00Z");

    @AfterEach
    void clearRequestContext() {
        RequestUserContextHolder.clear();
    }

    @Test
    void httpSendAcceptsRunAndUsesCanonicalRuntimePath() throws Exception {
        Fixture fixture = new Fixture();
        RequestUserContextHolder.set(new RequestUserContext(
                "user-1",
                "USER",
                System.currentTimeMillis() + 60_000
        ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(fixture.controller)
                .build();

        mvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionKey": " session-1 ",
                                  "message": "hello",
                                  "channel": "api",
                                  "responseMode": "agent"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestId").value(RUN_ID))
                .andExpect(jsonPath("$.data.sessionKey").value("session-1"))
                .andExpect(jsonPath("$.data.answer").value("http smoke answer"))
                .andExpect(jsonPath("$.data.model").value("provider:model"));

        RunState state = fixture.store.requireByRunId(RUN_ID);
        assertThat(state.status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(state.contextSnapshot()).isNotNull();
        assertThat(state.contextSnapshot().shortTermEvents()).contains("short-term turn");
        assertThat(state.contextSnapshot().semanticRecallItems()).contains("semantic fact");
        assertThat(state.contextSnapshot().activeLearningRules()).contains("procedural rule");
        assertThat(state.contextSnapshot().memoryBankText()).contains("project memory");
        assertThat(fixture.store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.VERIFICATION_COMPLETED,
                        RunEventType.RUN_DEGRADED
                );
        verifyNoInteractions(fixture.contextAssembler);
    }

    private static final class Fixture {
        private final InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        private final RunCoordinator coordinator = new RunCoordinator(store);
        private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
        private final ChatController controller;

        @SuppressWarnings("unchecked")
        private Fixture() {
            RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
            when(identityFactory.create()).thenReturn(RUN_ID);
            when(identityFactory.accept(any())).thenAnswer(invocation -> invocation.getArgument(0));

            AuthService authService = mock(AuthService.class);
            when(authService.resolveRoleByUserId("user-1")).thenReturn("USER");

            MemoryCoordinator memoryCoordinator = mock(MemoryCoordinator.class);
            SessionAccessClaim claim = claim();
            when(memoryCoordinator.retrieve(any(MemoryFrameRequest.class)))
                    .thenReturn(memoryResult(claim));

            AiProviderService aiProviderService = mock(AiProviderService.class);
            when(aiProviderService.activeClient()).thenReturn(new AiProviderService.ActiveChatClient(
                    "provider",
                    "model",
                    "https://example.test",
                    mock(ChatClient.class),
                    true,
                    ""
            ));

            ChatGuardService chatGuardService = mock(ChatGuardService.class);
            when(chatGuardService.acquireSessionLock("session-1")).thenReturn("lock-token");

            AgentEngine engine = mock(AgentEngine.class);
            when(engine.name()).thenReturn("simplified");
            when(engine.execute(any(), any())).thenReturn(new ChatExecutionResult(
                    "observe",
                    "plan",
                    "action",
                    "http smoke answer",
                    true
            ));
            EngineSelector engineSelector = mock(EngineSelector.class);
            when(engineSelector.select(any())).thenReturn(engine);

            ChatContextFactory contextFactory = contextFactory(
                    aiProviderService,
                    authService,
                    memoryCoordinator
            );
            RunLifecycleObserver observer = new RunLifecycleObserver(
                    new DefaultRunLifecycleBridge(coordinator),
                    new RollbackRunContextAdapter(),
                    new RunExecutionDecisionProjector(),
                    new RunResultProjector(),
                    true
            );
            ChatServiceImpl chatService = new ChatServiceImpl(
                    aiProviderService,
                    chatGuardService,
                    mock(OparLoopEngine.class),
                    mock(SimplifiedOparEngine.class),
                    mock(ChatResponsePolicyService.class),
                    mock(ModelTransportGuardService.class),
                    mock(LlmUsageRecordService.class),
                    mock(ConversationAdvisorSupport.class),
                    contextFactory,
                    mock(ChatResultPersister.class),
                    mock(MetaGuardExecutor.class),
                    mock(ToolOrchestrator.class),
                    mock(AgentActionProposalService.class),
                    mock(AgentRuntimeEngine.class),
                    engineSelector,
                    null,
                    mock(SseEventBridge.class),
                    mock(ToolInvocationProposalService.class),
                    observer,
                    identityFactory,
                    false,
                    true
            );
            this.controller = new ChatController(
                    chatService,
                    mock(ChatMessageProducer.class),
                    mock(AsyncChatResultStore.class),
                    mock(MessageEventService.class),
                    aiProviderService,
                    mock(AgentActionProposalService.class),
                    mock(AgentRunTraceService.class),
                    identityFactory,
                    authService,
                    new DefaultRunLifecycleBridge(coordinator)
            );
        }

        private ChatContextFactory contextFactory(
                AiProviderService aiProviderService,
                AuthService authService,
                MemoryCoordinator memoryCoordinator
        ) {
            AgentSessionService agentSessionService = mock(AgentSessionService.class);
            when(agentSessionService.getOrCreate("session-1", "api", "user-1"))
                    .thenAnswer(invocation -> {
                        com.springclaw.domain.entity.AgentSession session =
                                new com.springclaw.domain.entity.AgentSession();
                        session.setSessionKey(invocation.getArgument(0));
                        session.setChannel(invocation.getArgument(1));
                        session.setUserId(invocation.getArgument(2));
                        return session;
                    });
            SkillService skillService = mock(SkillService.class);
            Set<String> allowedToolPacks = Set.of("web");
            when(skillService.resolveAllowedToolPacks("api", "user-1"))
                    .thenReturn(allowedToolPacks);
            SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
            when(skillRegistryService.matchAgentVisibleDefinitions(
                    "hello",
                    allowedToolPacks,
                    2
            )).thenReturn(List.of());
            SoulPromptService soulPromptService = mock(SoulPromptService.class);
            when(soulPromptService.buildSystemPrompt("api", "user-1", List.of()))
                    .thenReturn("system prompt");
            AgentDecisionService decisionService = mock(AgentDecisionService.class);
            when(decisionService.decide(any(AgentDecisionRequest.class)))
                    .thenReturn(new AgentDecision(
                            "general",
                            "basic_model",
                            List.of("web"),
                            "read",
                            false,
                            "http smoke"
                    ));
            ChatRoutingStateService routingStateService =
                    mock(ChatRoutingStateService.class);
            when(routingStateService.resolveDefaultMode("simplified"))
                    .thenReturn("simplified");
            when(routingStateService.resolveAutoUpgrade(true)).thenReturn(true);
            ChatRoutingPolicyService routingPolicyService =
                    mock(ChatRoutingPolicyService.class);
            when(routingPolicyService.decide(
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
                    "http smoke",
                    "agent",
                    "general"
            ));

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
                    Clock.systemUTC()
            ));
            when(adapterProvider.getIfAvailable()).thenReturn(new LegacyContextViewAdapter());
            when(runStateProvider.getIfAvailable()).thenReturn(store);
            when(requestFactoryProvider.getIfAvailable())
                    .thenReturn(new RunStateContextSnapshotRequestFactory());
            when(projectorProvider.getIfAvailable())
                    .thenReturn(new CanonicalContextReadyProjector(coordinator, store));

            return new ChatContextFactory(
                    aiProviderService,
                    soulPromptService,
                    agentSessionService,
                    authService,
                    skillService,
                    skillRegistryService,
                    contextAssembler,
                    routingStateService,
                    routingPolicyService,
                    decisionService,
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
                Map.of("source", "http-smoke"),
                List.of(),
                T0.plusSeconds(1),
                "frame-hash-http-smoke"
        );
        return new MemoryFrameResult(
                frame,
                new MemoryRetrievalTrace(
                        RUN_ID,
                        scope,
                        frame.frameHash(),
                        Map.of("http-smoke", 4),
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
}
