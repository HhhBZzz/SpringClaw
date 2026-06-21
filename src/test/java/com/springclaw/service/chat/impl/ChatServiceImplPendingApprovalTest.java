package com.springclaw.service.chat.impl;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.proposal.PendingToolApprovalException;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplPendingApprovalTest {

    private final AiProviderService aiProviderService = mock(AiProviderService.class);
    private final ChatGuardService chatGuardService = mock(ChatGuardService.class);
    private final OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
    private final SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
    private final ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
    private final ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
    private final LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);
    private final ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
    private final ChatContextFactory chatContextFactory = mock(ChatContextFactory.class);
    private final ChatResultPersister chatResultPersister = mock(ChatResultPersister.class);
    private final MetaGuardExecutor metaGuardExecutor = mock(MetaGuardExecutor.class);
    private final ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
    private final AgentActionProposalService actionProposalService = mock(AgentActionProposalService.class);
    private final AgentRuntimeEngine agentRuntimeEngine = mock(AgentRuntimeEngine.class);
    private final EngineSelector engineSelector = mock(EngineSelector.class);
    private final LocalExecutionSupport localExecutionSupport = mock(LocalExecutionSupport.class);
    private final SseEventBridge sseEventBridge = mock(SseEventBridge.class);
    private final ToolInvocationProposalService proposalService = mock(ToolInvocationProposalService.class);

    @Test
    void handlePendingApproval_proposalExists_sendsActionRequired() {
        ChatServiceImpl chatService = buildService();
        SseEmitter emitter = new SseEmitter();
        ChatRequest request = new ChatRequest("session-A", "user-1", "write file", "api");
        ToolInvocationProposal proposal = proposal("tip-1");
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));

        chatService.handlePendingApproval(
                emitter,
                request,
                "lock-token",
                new AtomicBoolean(false),
                new PendingToolApprovalException("tip-1")
        );

        verify(sseEventBridge).sendToolActionRequired(emitter, proposal);
        verify(chatGuardService).releaseSessionLock("session-A", "lock-token");
        verify(sseEventBridge).completeEmitter(emitter);
    }

    @Test
    void handlePendingApproval_proposalNotFound_stillReleasesAndCompletes() {
        ChatServiceImpl chatService = buildService();
        SseEmitter emitter = new SseEmitter();
        ChatRequest request = new ChatRequest("session-A", "user-1", "write file", "api");
        when(proposalService.findByProposalId("tip-missing")).thenReturn(Optional.empty());

        chatService.handlePendingApproval(
                emitter,
                request,
                "lock-token",
                new AtomicBoolean(false),
                new PendingToolApprovalException("tip-missing")
        );

        verify(sseEventBridge, never()).sendToolActionRequired(eq(emitter), any(ToolInvocationProposal.class));
        verify(chatGuardService).releaseSessionLock("session-A", "lock-token");
        verify(sseEventBridge).completeEmitter(emitter);
    }

    private ChatServiceImpl buildService() {
        return new ChatServiceImpl(
                aiProviderService,
                chatGuardService,
                oparLoopEngine,
                simplifiedOparEngine,
                chatResponsePolicyService,
                modelTransportGuardService,
                llmUsageRecordService,
                conversationAdvisorSupport,
                chatContextFactory,
                chatResultPersister,
                metaGuardExecutor,
                toolOrchestrator,
                actionProposalService,
                agentRuntimeEngine,
                engineSelector,
                localExecutionSupport,
                sseEventBridge,
                proposalService,
                new DefaultRunIdentityFactory(),
                false,
                true
        );
    }

    private static ToolInvocationProposal proposal(String proposalId) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                1L,
                proposalId,
                "req-1",
                "run-1",
                "session-A",
                "user-1",
                "USER",
                "WorkspaceTool.writeFile",
                "workspace",
                "{}",
                "hash",
                "HIGH",
                List.of("README.md"),
                "preview",
                false,
                List.of(),
                ToolInvocationProposalStatus.PENDING,
                0,
                null,
                null,
                null,
                "head-sha",
                null,
                null,
                List.of(),
                null,
                null,
                now,
                now,
                now.plusMinutes(15)
        );
    }
}
