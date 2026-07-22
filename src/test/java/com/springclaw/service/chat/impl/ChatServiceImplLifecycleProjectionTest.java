package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.DefaultRunLifecycleBridge;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.bridge.RunExecutionDecisionProjector;
import com.springclaw.runtime.bridge.RollbackRunContextAdapter;
import com.springclaw.runtime.bridge.RunResultProjector;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentActionProposal;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the canonical lifecycle projection of legacy chat execution. The observer
 * is the real Spring bean backed by the process-local lifecycle store; legacy
 * execution behavior (engine selection, persistence, final answer) is mocked.
 */
class ChatServiceImplLifecycleProjectionTest {

    private static final String RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void blockingExecutionProjectsToDegradedWithUnchangedAnswer() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        RunLifecycleObserver observer = new RunLifecycleObserver(
                new DefaultRunLifecycleBridge(coordinator),
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                false
        );
        Fixture f = new Fixture(observer);
        ChatContext context = f.context();
        AgentEngine engine = mock(AgentEngine.class);
        when(engine.name()).thenReturn("simplified");
        when(engine.execute(any(), any())).thenReturn(
                new ChatExecutionResult("observe", "PLAN", "ACTION", "answer", true));
        when(f.engineSelector.select(any(), any())).thenReturn(engine);
        when(f.chatContextFactory.build(any(ChatRequest.class), anyBoolean(), anyString()))
                .thenReturn(context);

        com.springclaw.dto.chat.ChatResponse response =
                f.service.chat(new ChatRequest("s1", "u1", "你好", "api"));

        assertThat(response.answer()).isEqualTo("answer");
        assertThat(response.requestId()).isEqualTo(RUN_ID);
        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.DEGRADED);
    }

    @Test
    void persistResultFalseStillReachesTerminalDegraded() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        RunLifecycleObserver observer = new RunLifecycleObserver(
                new DefaultRunLifecycleBridge(coordinator),
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                false
        );
        Fixture f = new Fixture(observer);
        ChatContext context = f.context();
        AgentEngine engine = mock(AgentEngine.class);
        when(engine.name()).thenReturn("simplified");
        when(engine.execute(any(), any())).thenReturn(
                new ChatExecutionResult("observe", "PLAN", "ACTION", "answer", true));
        when(f.engineSelector.select(any(), any())).thenReturn(engine);
        when(f.chatContextFactory.build(any(ChatRequest.class), anyBoolean(), anyString()))
                .thenReturn(context);

        ChatServiceImpl.TaskChatExecutionResult outcome =
                f.service.executeTaskMessage(new ChatRequest("s1", "u1", "你好", "api"), false, RUN_ID);

        assertThat(outcome.answer()).isEqualTo("answer");
        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.DEGRADED);
    }

    @Test
    void blockingExecutionFailureProjectsToFailed() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        acceptRun(coordinator);
        RunLifecycleObserver observer = new RunLifecycleObserver(
                new DefaultRunLifecycleBridge(coordinator),
                new RollbackRunContextAdapter(),
                new RunExecutionDecisionProjector(),
                new RunResultProjector(),
                false
        );
        Fixture f = new Fixture(observer);
        ChatContext context = f.context();
        AgentEngine engine = mock(AgentEngine.class);
        when(engine.name()).thenReturn("simplified");
        when(engine.execute(any(), any()))
                .thenThrow(new IllegalStateException("engine blew up"));
        when(f.engineSelector.select(any(), any())).thenReturn(engine);
        when(f.chatContextFactory.build(any(ChatRequest.class), anyBoolean(), anyString()))
                .thenReturn(context);

        try {
            f.service.chat(new ChatRequest("s1", "u1", "你好", "api"));
        } catch (RuntimeException expected) {
            // legacy failure propagates
        }

        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.FAILED);
    }

    private static void acceptRun(RunCoordinator coordinator) {
        Instant now = Instant.now();
        coordinator.accept(new RunAcceptance(
                RUN_ID, "s1", "api", "u1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "s1",
                        "u1"
                ),
                "USER", "你好", "agent",
                now, now.plusSeconds(1800),
                null
        ));
    }

    private static final class Fixture {
        final AiProviderService aiProviderService = mock(AiProviderService.class);
        final ChatGuardService chatGuardService = mock(ChatGuardService.class);
        final OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        final SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
        final ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        final ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        final LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);
        final ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        final ChatContextFactory chatContextFactory = mock(ChatContextFactory.class);
        final ChatResultPersister chatResultPersister = mock(ChatResultPersister.class);
        final MetaGuardExecutor metaGuardExecutor = mock(MetaGuardExecutor.class);
        final ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        final AgentActionProposalService actionProposalService = mock(AgentActionProposalService.class);
        final AgentRuntimeEngine agentRuntimeEngine = mock(AgentRuntimeEngine.class);
        final SseEventBridge sseEventBridge = mock(SseEventBridge.class);
        final EngineSelector engineSelector = mock(EngineSelector.class);
        final RunIdentityFactory runIdentityFactory = mock(RunIdentityFactory.class);
        final ChatServiceImpl service;

        Fixture(RunLifecycleObserver observer) {
            AgentSession session = new AgentSession();
            session.setSessionKey("s1");
            session.setUserId("u1");
            session.setChannel("api");
            AssembledContext assembled = new AssembledContext(
                    "s1", "api", "u1", "你好", "- USER: 你好",
                    "（暂无长期语义记忆）", "# 当前问题\n你好");
            AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                    "deepseek", "deepseek-chat", "https://api.deepseek.com",
                    mock(ChatClient.class), true, "");
            when(chatGuardService.acquireSessionLock("s1")).thenReturn("lock");
            when(aiProviderService.activeClient()).thenReturn(activeClient);
            when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
            when(runIdentityFactory.create()).thenReturn(RUN_ID);
            when(runIdentityFactory.accept(anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            service = new ChatServiceImpl(
                    aiProviderService, chatGuardService, oparLoopEngine, simplifiedOparEngine,
                    chatResponsePolicyService, modelTransportGuardService, llmUsageRecordService,
                    conversationAdvisorSupport, chatContextFactory, chatResultPersister,
                    metaGuardExecutor, toolOrchestrator, actionProposalService, agentRuntimeEngine,
                    engineSelector, null, sseEventBridge, null, observer, runIdentityFactory,
                    false, true);
        }

        ChatContext context() {
            AgentSession session = new AgentSession();
            session.setSessionKey("s1");
            session.setUserId("u1");
            session.setChannel("api");
            AssembledContext assembled = new AssembledContext(
                    "s1", "api", "u1", "你好", "- USER: 你好",
                    "（暂无长期语义记忆）", "# 当前问题\n你好");
            AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
            return new ChatContext(
                    session, "api", "u1", "USER", "你好", "你好", RUN_ID, "system",
                    assembled, activeClient, "simplified", "默认", "agent", "general",
                    AgentDecision.general("默认"));
        }
    }
}
