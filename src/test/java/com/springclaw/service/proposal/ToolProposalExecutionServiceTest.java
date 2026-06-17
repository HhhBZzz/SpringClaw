package com.springclaw.service.proposal;

import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolProposalExecutionServiceTest {

    private ToolInvocationProposalService proposalService;
    private ToolInvoker toolInvoker;
    private ToolProposalExecutionService executor;

    @BeforeEach
    void setUp() {
        proposalService = Mockito.mock(ToolInvocationProposalService.class);
        toolInvoker = Mockito.mock(ToolInvoker.class);
        executor = new ToolProposalExecutionService(proposalService, toolInvoker);
    }

    @Test
    void onExecutionRequested_setsBothContextsBeforeInvokingTool() {
        ToolInvocationProposal proposal = sampleExecutingProposal("tip-1");
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));

        AtomicReference<ToolExecutionContext> ctxDuringInvoke = new AtomicReference<>();
        AtomicReference<ApprovedProposalContext> approvedDuringInvoke = new AtomicReference<>();
        when(toolInvoker.invoke(Mockito.anyString(), Mockito.anyString())).thenAnswer(inv -> {
            ctxDuringInvoke.set(ToolExecutionContextHolder.get());
            approvedDuringInvoke.set(ToolExecutionContextHolder.getApprovedProposal());
            return "ok";
        });

        executor.onExecutionRequested(new ToolProposalExecutionRequestedEvent("tip-1"));

        // 调用期间 ToolExecutionContext 必须就位
        assertThat(ctxDuringInvoke.get()).isNotNull();
        assertThat(ctxDuringInvoke.get().userId()).isEqualTo("u1");
        assertThat(ctxDuringInvoke.get().sessionKey()).isEqualTo("session-A");
        assertThat(ctxDuringInvoke.get().requestId()).isEqualTo("req-1");
        assertThat(ctxDuringInvoke.get().runId()).isEqualTo("run-1");
        assertThat(ctxDuringInvoke.get().phase()).isEqualTo("proposal-execution");

        // 调用期间 ApprovedProposalContext 也必须就位
        assertThat(approvedDuringInvoke.get()).isNotNull();
        assertThat(approvedDuringInvoke.get().proposalId()).isEqualTo("tip-1");

        // 调用结束后两个 ThreadLocal 都被清空
        assertThat(ToolExecutionContextHolder.get()).isNull();
        assertThat(ToolExecutionContextHolder.getApprovedProposal()).isNull();
    }

    @Test
    void onExecutionRequested_clearsContextsEvenWhenInvokerThrows() {
        ToolInvocationProposal proposal = sampleExecutingProposal("tip-2");
        when(proposalService.findByProposalId("tip-2")).thenReturn(Optional.of(proposal));
        when(toolInvoker.invoke(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new RuntimeException("tool boom"));

        executor.onExecutionRequested(new ToolProposalExecutionRequestedEvent("tip-2"));

        verify(proposalService).markFailed(Mockito.eq("tip-2"), Mockito.contains("tool boom"));
        assertThat(ToolExecutionContextHolder.get()).isNull();
        assertThat(ToolExecutionContextHolder.getApprovedProposal()).isNull();
    }

    @Test
    void onExecutionRequested_proposalNotFound_skips() {
        when(proposalService.findByProposalId("ghost")).thenReturn(Optional.empty());

        executor.onExecutionRequested(new ToolProposalExecutionRequestedEvent("ghost"));

        verify(toolInvoker, Mockito.never()).invoke(Mockito.anyString(), Mockito.anyString());
        verify(proposalService, Mockito.never()).markFailed(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void onExecutionRequested_statusNotExecuting_skips() {
        ToolInvocationProposal pending = sampleExecutingProposal("tip-3");
        ToolInvocationProposal modified = new ToolInvocationProposal(
                pending.id(), pending.proposalId(), pending.requestId(), pending.runId(),
                pending.sessionKey(), pending.userId(), pending.roleCode(),
                pending.toolName(), pending.toolsetId(),
                pending.argumentsCanonicalJson(), pending.argumentsHash(), pending.riskLevel(),
                pending.targetPaths(), pending.previewSummary(),
                pending.workspaceDirtyAtCreate(), pending.dirtyFilesAtCreate(),
                ToolInvocationProposalStatus.PENDING, pending.version(),
                pending.executedAt(), pending.executionResult(), pending.executionError(),
                pending.gitHeadShaAtCreate(), pending.gitBaselineSha(), pending.gitCommitSha(),
                pending.gitChangedFiles(), pending.reviewedAt(), pending.reviewReason(),
                pending.createTime(), pending.updateTime(), pending.expiresAt()
        );
        when(proposalService.findByProposalId("tip-3")).thenReturn(Optional.of(modified));

        executor.onExecutionRequested(new ToolProposalExecutionRequestedEvent("tip-3"));

        verify(toolInvoker, Mockito.never()).invoke(Mockito.anyString(), Mockito.anyString());
    }

    private ToolInvocationProposal sampleExecutingProposal(String proposalId) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                null, proposalId, "req-1", "run-1",
                "session-A", "u1", "USER",
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                "[\"src/A.java\",\"hello\"]", "abcd",
                "write", List.of("src/A.java"), "preview",
                false, List.of(),
                ToolInvocationProposalStatus.EXECUTING, 0,
                null, null, null,
                "headSha", null, null, List.of(),
                null, null,
                now, now, now.plusMinutes(15)
        );
    }
}
