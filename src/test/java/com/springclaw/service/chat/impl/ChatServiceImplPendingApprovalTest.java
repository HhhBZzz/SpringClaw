package com.springclaw.service.chat.impl;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.proposal.PendingToolApprovalException;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final LocalFileWriteProposalService localFileWriteProposalService = mock(LocalFileWriteProposalService.class);
    private final ApprovedCommandProposalService approvedCommandProposalService = mock(ApprovedCommandProposalService.class);

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

    @Test
    void streamActionRequired_desktopWrite_usesToolProposalInsteadOfLegacyActionProposal() throws Exception {
        ChatServiceImpl chatService = buildService();
        chatService.setLocalFileWriteProposalService(localFileWriteProposalService);
        SseEmitter emitter = new SseEmitter();
        ChatContext context = desktopWriteContext();
        ToolInvocationProposal proposal = proposal("tip-desktop");
        when(localFileWriteProposalService.createProposalIfSupported(context)).thenReturn(Optional.of(proposal));

        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "streamActionRequired", ChatContext.class, String.class, AtomicBoolean.class, SseEmitter.class);
        method.setAccessible(true);
        method.invoke(chatService, context, "lock-token", new AtomicBoolean(false), emitter);

        verify(sseEventBridge).sendToolActionRequired(emitter, proposal);
        verify(actionProposalService, never()).createProposal(
                any(), any(), any(), any(), any(), any(), any());
        verify(chatGuardService).releaseSessionLock("session-A", "lock-token");
        verify(sseEventBridge).completeEmitter(emitter);
    }

    @Test
    void streamActionRequired_approvedCommand_usesToolProposalInsteadOfLegacyActionProposal() throws Exception {
        ChatServiceImpl chatService = buildService();
        chatService.setApprovedCommandProposalService(approvedCommandProposalService);
        SseEmitter emitter = new SseEmitter();
        ChatContext context = approvedCommandContext();
        ToolInvocationProposal proposal = proposal("tip-command");
        when(approvedCommandProposalService.createProposalIfSupported(context)).thenReturn(Optional.of(proposal));

        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "streamActionRequired", ChatContext.class, String.class, AtomicBoolean.class, SseEmitter.class);
        method.setAccessible(true);
        method.invoke(chatService, context, "lock-token", new AtomicBoolean(false), emitter);

        verify(approvedCommandProposalService).createProposalIfSupported(context);
        verify(sseEventBridge).sendToolActionRequired(eq(emitter), eq(proposal));
        verify(sseEventBridge).sendAnswerChunks(eq(emitter),
                eq("这个工具操作需要确认。请在确认卡片里确认或拒绝；确认前不会执行。"));
        verify(actionProposalService, never()).createProposal(
                any(), any(), any(), any(), any(), any(), any());
        verify(chatGuardService).releaseSessionLock("session-A", "lock-token");
        verify(sseEventBridge).completeEmitter(emitter);
    }

    @Test
    void askClarificationDecisionDoesNotBecomeActionConfirmation() throws Exception {
        ChatServiceImpl chatService = buildService();
        AgentDecision ambiguousDecision = new AgentDecision(
                "model_control",
                "ask_clarification",
                List.of("system"),
                "side_effect",
                true,
                "模型分类结果不明确，需要澄清。"
        );

        Method method = ChatServiceImpl.class.getDeclaredMethod("shouldRequestActionConfirmation", ChatContext.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(chatService, contextWith(ambiguousDecision))).isFalse();
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
                null,
                new DefaultRunIdentityFactory(),
                false,
                true
        );
    }

    private static ChatContext contextWith(AgentDecision decision) {
        AgentSession session = new AgentSession();
        session.setSessionKey("session-A");
        session.setChannel("api");
        session.setUserId("user-1");
        AssembledContext assembled = new AssembledContext(
                "session-A",
                "api",
                "user-1",
                "切换 DeepSeek 模型",
                "",
                "",
                "# 当前问题\n切换 DeepSeek 模型"
        );
        return new ChatContext(
                session,
                "api",
                "user-1",
                "USER",
                "切换 DeepSeek 模型",
                "切换 DeepSeek 模型",
                "req-1",
                "system",
                assembled,
                new AiProviderService.ActiveChatClient("deepseek", "deepseek-v4-pro", "https://example.test", null, true, ""),
                "opar",
                "test",
                "agent",
                decision.intent(),
                decision
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

    private static ChatContext desktopWriteContext() {
        String message = "你在桌面创建一个 hello.txt 文件，内容是 hello springclaw";
        AgentSession session = new AgentSession();
        session.setSessionKey("session-A");
        session.setChannel("api");
        session.setUserId("admin");
        return new ChatContext(
                session,
                "api",
                "admin",
                "ADMIN",
                message,
                message,
                "req-1",
                "system",
                new AssembledContext("session-A", "api", "admin", message, "", "", ""),
                null,
                "simplified",
                "route",
                "agent",
                "local_files",
                new AgentDecision("local_files", "agent_tools", List.of("file"), "write", true, "desktop write")
        );
    }

    private static ChatContext approvedCommandContext() {
        String message = "请执行命令 pwd";
        AgentSession session = new AgentSession();
        session.setSessionKey("session-A");
        session.setChannel("api");
        session.setUserId("admin");
        return new ChatContext(
                session,
                "api",
                "admin",
                "ADMIN",
                message,
                message,
                "req-1",
                "system",
                new AssembledContext("session-A", "api", "admin", message, "", "", ""),
                null,
                "simplified",
                "route",
                "agent",
                "model_control",
                new AgentDecision("model_control", "agent_tools", List.of("system"), "execution", true,
                        "command confirmation")
        );
    }
}
