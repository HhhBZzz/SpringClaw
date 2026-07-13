package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.service.proposal.ToolInvocationSnapshot;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovedCommandProposalServiceTest {

    private final ToolInvocationSnapshotService snapshotService = mock(ToolInvocationSnapshotService.class);
    private final ToolInvocationProposalService proposalService = mock(ToolInvocationProposalService.class);
    private final ApprovedCommandProposalService service =
            new ApprovedCommandProposalService(snapshotService, proposalService);

    @Test
    void createProposalIfSupported_freezesEchoCommandAndRequesterIdentity() {
        ChatContext context = context("  请执行命令 echo springclaw-approval-e2e  ");
        ToolInvocationSnapshot snapshot = snapshot();
        ToolInvocationProposal proposal = proposal("tip-command");
        ArgumentCaptor<String> toolNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toolsetCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> riskCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolExecutionContext> contextCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        when(snapshotService.capture(
                toolNameCaptor.capture(),
                toolsetCaptor.capture(),
                argsCaptor.capture(),
                riskCaptor.capture()
        )).thenReturn(snapshot);
        when(proposalService.createPending(any(ToolInvocationSnapshot.class), contextCaptor.capture()))
                .thenReturn(proposal);

        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(context);

        assertThat(result).contains(proposal);
        assertThat(toolNameCaptor.getValue()).isEqualTo("SystemToolPack.runCommand");
        assertThat(toolsetCaptor.getValue()).isEqualTo("system");
        assertThat(riskCaptor.getValue()).isEqualTo("execution");
        assertThat(argsCaptor.getValue()).containsExactly("echo springclaw-approval-e2e");
        assertThat(contextCaptor.getValue())
                .extracting(
                        ToolExecutionContext::sessionKey,
                        ToolExecutionContext::channel,
                        ToolExecutionContext::userId,
                        ToolExecutionContext::requestId,
                        ToolExecutionContext::runId,
                        ToolExecutionContext::roleCode
                )
                .containsExactly("session-command", "api", "owner-command", "request-command", "request-command", "ADMIN");
        verify(proposalService).createPending(snapshot, contextCaptor.getValue());
    }

    @ParameterizedTest
    @MethodSource("approvedFixedCommands")
    void createProposalIfSupported_acceptsApprovedFixedCommands(String command) {
        ToolInvocationSnapshot snapshot = snapshot();
        ToolInvocationProposal proposal = proposal("tip-" + command.replace(' ', '-'));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        when(snapshotService.capture(any(), any(), argsCaptor.capture(), any())).thenReturn(snapshot);
        when(proposalService.createPending(any(ToolInvocationSnapshot.class), any(ToolExecutionContext.class)))
                .thenReturn(proposal);

        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(context("请执行命令 " + command));

        assertThat(result).contains(proposal);
        assertThat(argsCaptor.getValue()).containsExactly(command);
        verify(proposalService).createPending(eq(snapshot), any(ToolExecutionContext.class));
    }

    @ParameterizedTest
    @MethodSource("unsupportedMessages")
    void createProposalIfSupported_rejectsUnsupportedInputWithoutDurableSideEffects(String message) {
        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(context(message));

        assertThat(result).isEmpty();
        verifyNoInteractions(snapshotService, proposalService);
    }

    @ParameterizedTest
    @MethodSource("nonExecutableDecisions")
    void createProposalIfSupported_rejectsDecisionsOutsideExecutionRiskModelControl(AgentDecision decision) {
        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(
                context("请执行命令 pwd", decision)
        );

        assertThat(result).isEmpty();
        verifyNoInteractions(snapshotService, proposalService);
    }

    private static Stream<String> approvedFixedCommands() {
        return Stream.of("pwd", "git status");
    }

    private static Stream<String> unsupportedMessages() {
        return Stream.of(
                "请执行命令 rm -rf /",
                "请执行命令 git status; pwd",
                "请执行命令 echo $(whoami)",
                "请执行命令 curl https://example.com",
                "请执行 pwd",
                "请执行命令 echo safe\\text",
                "请执行命令 echo safe\ntext",
                "请执行命令 echo safe|text",
                "请执行命令 echo safe&text",
                "请执行命令 echo safe<text",
                "请执行命令 echo safe>text",
                "请执行命令 echo safe$text",
                "请执行命令 echo safe(text",
                "请执行命令 echo safe)text",
                "请执行命令 echo safe{text",
                "请执行命令 echo safe}text",
                "请执行命令 echo safe[text",
                "请执行命令 echo safe]text"
        );
    }

    private static Stream<AgentDecision> nonExecutableDecisions() {
        return Stream.of(
                new AgentDecision("workspace_analysis", "agent_tools", List.of("system"), "execution", true, "wrong intent"),
                new AgentDecision("model_control", "agent_tools", List.of("system"), "read", true, "wrong risk"),
                new AgentDecision("model_control", "agent_tools", List.of("system"), "execution", false, "confirmation missing")
        );
    }

    private static ChatContext context(String message) {
        return context(message, new AgentDecision(
                "model_control", "agent_tools", List.of("system"), "execution", true, "command confirmation"
        ));
    }

    private static ChatContext context(String message, AgentDecision decision) {
        AgentSession session = new AgentSession();
        session.setSessionKey("session-command");
        session.setChannel("api");
        session.setUserId("owner-command");
        return new ChatContext(
                session,
                "api",
                "owner-command",
                "ADMIN",
                message,
                message,
                "request-command",
                "system",
                new AssembledContext("session-command", "api", "owner-command", message, "", "", ""),
                null,
                "simplified",
                "route",
                "agent",
                decision.intent(),
                decision
        );
    }

    private static ToolInvocationSnapshot snapshot() {
        return new ToolInvocationSnapshot(
                "SystemToolPack.runCommand",
                "system",
                "[\"echo springclaw-approval-e2e\"]",
                "hash",
                "execution",
                List.of(),
                "runCommand: echo springclaw-approval-e2e",
                false,
                Set.of(),
                "head-sha"
        );
    }

    private static ToolInvocationProposal proposal(String proposalId) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                1L,
                proposalId,
                "request-command",
                "request-command",
                "session-command",
                "owner-command",
                "ADMIN",
                "SystemToolPack.runCommand",
                "system",
                "[\"echo springclaw-approval-e2e\"]",
                "hash",
                "execution",
                List.of(),
                "runCommand: echo springclaw-approval-e2e",
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
