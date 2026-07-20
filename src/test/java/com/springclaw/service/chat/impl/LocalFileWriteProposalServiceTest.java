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
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalFileWriteProposalServiceTest {

    private final ToolInvocationSnapshotService snapshotService = mock(ToolInvocationSnapshotService.class);
    private final ToolInvocationProposalService proposalService = mock(ToolInvocationProposalService.class);
    private final LocalFileWritePlanner planner = mock(LocalFileWritePlanner.class);
    private final LocalFileWriteProposalService service =
            new LocalFileWriteProposalService(snapshotService, proposalService, planner);

    @Test
    void createProposalIfSupported_convertsPlannerOutputToFileToolProposal() {
        ChatContext context = context("请在桌面创建一个总结文档，内容由你整理");
        ToolInvocationSnapshot snapshot = snapshot();
        ToolInvocationProposal proposal = proposal("tip-demo");
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<ToolExecutionContext> contextCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        when(planner.plan(context)).thenReturn(Optional.of(new LocalFileWritePlan(
                "Desktop/summary.md",
                "模型生成的正文",
                true,
                "用户要求写本地文档"
        )));
        when(snapshotService.capture(
                eq("FileToolPack.writeTextFile"),
                eq("file"),
                argsCaptor.capture(),
                eq("write")
        )).thenReturn(snapshot);
        when(proposalService.createPending(eq(snapshot), contextCaptor.capture())).thenReturn(proposal);

        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(context);

        assertThat(result).contains(proposal);
        assertThat(argsCaptor.getValue()).containsExactly("Desktop/summary.md", "模型生成的正文", true);
        assertThat(contextCaptor.getValue().sessionKey()).isEqualTo("session-A");
        assertThat(contextCaptor.getValue().userId()).isEqualTo("admin");
        assertThat(contextCaptor.getValue().roleCode()).isEqualTo("ADMIN");
    }

    @Test
    void createProposalIfSupported_ignoresNonWriteDecisions() {
        ChatContext context = context(
                "看看桌面有什么文件",
                new AgentDecision("local_files", "agent_tools", List.of("file"), "read", false, "read")
        );

        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(context);

        assertThat(result).isEmpty();
        verifyNoInteractions(planner, snapshotService, proposalService);
    }

    @Test
    void createProposalIfSupported_rejectsUnsafePlannerPath() {
        ChatContext context = context("把内容写到 ../secret.txt");
        when(planner.plan(context)).thenReturn(Optional.of(new LocalFileWritePlan(
                "../secret.txt",
                "secret",
                true,
                "unsafe"
        )));

        Optional<ToolInvocationProposal> result = service.createProposalIfSupported(context);

        assertThat(result).isEmpty();
        verifyNoInteractions(snapshotService, proposalService);
    }

    private static ChatContext context(String message) {
        return context(message, new AgentDecision("local_files", "agent_tools", List.of("local-files", "file"), "write", true, "write"));
    }

    private static ChatContext context(String message, AgentDecision decision) {
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
                decision.intent(),
                decision
        );
    }

    private static ToolInvocationSnapshot snapshot() {
        return new ToolInvocationSnapshot(
                "FileToolPack.writeTextFile",
                "file",
                "[\"Desktop/summary.md\",\"模型生成的正文\",true]",
                "hash",
                "write",
                List.of("Desktop/summary.md"),
                "writeTextFile: Desktop/summary.md",
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
                "req-1",
                "req-1",
                "session-A",
                "admin",
                "ADMIN",
                "FileToolPack.writeTextFile",
                "file",
                "[\"Desktop/summary.md\",\"模型生成的正文\",true]",
                "hash",
                "write",
                List.of("Desktop/summary.md"),
                "writeTextFile: Desktop/summary.md",
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
