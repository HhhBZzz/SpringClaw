package com.springclaw.service.proposal;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7 守护测试：ToolInvocationProposalService 的 confirm / reject 状态机。
 *
 * 不变量覆盖：
 *   - 不变量 8：终态不可重新执行（confirm 在非 PENDING 必须抛错）
 *   - 不变量 9：FAILED 不重试（confirm 状态非法分支）
 *   - 不变量 16：confirm 事务内 PENDING → APPROVED → EXECUTING + 发 ToolProposalExecutionRequestedEvent
 */
class ToolInvocationProposalServiceConfirmTest {

    private ToolInvocationProposalRepository repository;
    private ApplicationEventPublisher publisher;
    private ToolInvocationProposalService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ToolInvocationProposalRepository.class);
        publisher = Mockito.mock(ApplicationEventPublisher.class);
        service = new ToolInvocationProposalService(repository, publisher);
    }

    @Test
    void confirm_pendingToExecutingAndPublishesEvent() {
        String pid = "tip-1";
        ToolInvocationProposal pending = pendingProposal(pid, 0, LocalDateTime.now().plusMinutes(10));
        ToolInvocationProposal executing = withStatusAndVersion(
                pending, ToolInvocationProposalStatus.EXECUTING, 2);

        when(repository.findByProposalId(pid))
                .thenReturn(Optional.of(pending))    // initial fetch
                .thenReturn(Optional.of(executing)); // post-CAS fetch
        when(repository.compareAndSetStatus(eq(pid),
                eq(ToolInvocationProposalStatus.PENDING),
                eq(ToolInvocationProposalStatus.APPROVED),
                eq(0), eq("ok"))).thenReturn(true);
        when(repository.compareAndSetStatus(eq(pid),
                eq(ToolInvocationProposalStatus.APPROVED),
                eq(ToolInvocationProposalStatus.EXECUTING),
                eq(1), Mockito.isNull())).thenReturn(true);

        ToolInvocationProposal result = service.confirm(pid, "ok");

        assertThat(result.status()).isEqualTo(ToolInvocationProposalStatus.EXECUTING);
        ArgumentCaptor<ToolProposalExecutionRequestedEvent> ev =
                ArgumentCaptor.forClass(ToolProposalExecutionRequestedEvent.class);
        verify(publisher, times(1)).publishEvent(ev.capture());
        assertThat(ev.getValue().proposalId()).isEqualTo(pid);
    }

    @Test
    void createPendingCopiesCanonicalIdentityIntoProposalRow() {
        String canonicalId = "55555555555555555555555555555555";
        ToolInvocationSnapshot snapshot = new ToolInvocationSnapshot(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                "[\"docs/a.md\",\"content\"]",
                "hash",
                "write",
                List.of("docs/a.md"),
                "write docs/a.md",
                false,
                Set.of(),
                "head"
        );
        ToolExecutionContext context = new ToolExecutionContext(
                "session-A",
                "api",
                "u1",
                canonicalId,
                "ACT-1",
                canonicalId,
                "USER"
        );
        when(repository.insert(any(ToolInvocationProposal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ToolInvocationProposal proposal = service.createPending(snapshot, context);

        assertThat(proposal.requestId()).isEqualTo(canonicalId);
        assertThat(proposal.runId()).isEqualTo(canonicalId);
        assertThat(proposal.sessionKey()).isEqualTo("session-A");
        assertThat(proposal.userId()).isEqualTo("u1");
    }

    @Test
    void confirm_alreadyApproved_throws40409() {
        String pid = "tip-2";
        ToolInvocationProposal approved = withStatusAndVersion(
                pendingProposal(pid, 1, LocalDateTime.now().plusMinutes(10)),
                ToolInvocationProposalStatus.APPROVED, 1);
        when(repository.findByProposalId(pid)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.confirm(pid, "ok"))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getCode() == 40409)
                .hasMessageContaining("状态非法");

        verify(publisher, never()).publishEvent(any());
        verify(repository, never()).compareAndSetStatus(anyString(), any(), any(), anyInt(), any());
    }

    @Test
    void confirm_expired_throws40410() {
        String pid = "tip-3";
        ToolInvocationProposal expired = pendingProposal(pid, 0, LocalDateTime.now().minusMinutes(1));
        when(repository.findByProposalId(pid)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.confirm(pid, "ok"))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getCode() == 40410)
                .hasMessageContaining("已过期");

        verify(publisher, never()).publishEvent(any());
        verify(repository, never()).compareAndSetStatus(anyString(), any(), any(), anyInt(), any());
    }

    @Test
    void confirm_firstCASFails_throws40409() {
        String pid = "tip-4";
        ToolInvocationProposal pending = pendingProposal(pid, 0, LocalDateTime.now().plusMinutes(10));
        when(repository.findByProposalId(pid)).thenReturn(Optional.of(pending));
        when(repository.compareAndSetStatus(eq(pid),
                eq(ToolInvocationProposalStatus.PENDING),
                eq(ToolInvocationProposalStatus.APPROVED),
                eq(0), eq("ok"))).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(pid, "ok"))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getCode() == 40409)
                .hasMessageContaining("状态变更失败");

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void confirm_secondCASFails_throws40409() {
        String pid = "tip-5";
        ToolInvocationProposal pending = pendingProposal(pid, 0, LocalDateTime.now().plusMinutes(10));
        when(repository.findByProposalId(pid)).thenReturn(Optional.of(pending));
        when(repository.compareAndSetStatus(eq(pid),
                eq(ToolInvocationProposalStatus.PENDING),
                eq(ToolInvocationProposalStatus.APPROVED),
                eq(0), eq("ok"))).thenReturn(true);
        when(repository.compareAndSetStatus(eq(pid),
                eq(ToolInvocationProposalStatus.APPROVED),
                eq(ToolInvocationProposalStatus.EXECUTING),
                eq(1), Mockito.isNull())).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(pid, "ok"))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getCode() == 40409)
                .hasMessageContaining("进入执行状态失败");

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void reject_pendingToRejected() {
        String pid = "tip-6";
        ToolInvocationProposal pending = pendingProposal(pid, 0, LocalDateTime.now().plusMinutes(10));
        ToolInvocationProposal rejected = withStatusAndVersion(
                pending, ToolInvocationProposalStatus.REJECTED, 1);

        when(repository.findByProposalId(pid))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(rejected));
        when(repository.compareAndSetStatus(eq(pid),
                eq(ToolInvocationProposalStatus.PENDING),
                eq(ToolInvocationProposalStatus.REJECTED),
                eq(0), eq("nope"))).thenReturn(true);

        ToolInvocationProposal result = service.reject(pid, "nope");

        assertThat(result.status()).isEqualTo(ToolInvocationProposalStatus.REJECTED);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void reject_alreadyApproved_throws40409() {
        String pid = "tip-7";
        ToolInvocationProposal approved = withStatusAndVersion(
                pendingProposal(pid, 1, LocalDateTime.now().plusMinutes(10)),
                ToolInvocationProposalStatus.APPROVED, 1);
        when(repository.findByProposalId(pid)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.reject(pid, "nope"))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getCode() == 40409)
                .hasMessageContaining("状态非法");

        verify(publisher, never()).publishEvent(any());
    }

    private ToolInvocationProposal pendingProposal(String id, int version, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                null, id, "req-1", "run-1",
                "session-A", "u1", "USER",
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                "[\"src/A.java\",\"hello\"]", "abcd",
                "write", List.of("src/A.java"), "preview",
                false, List.of(),
                ToolInvocationProposalStatus.PENDING, version,
                null, null, null,
                "headSha", null, null, List.of(),
                null, null,
                now, now, expiresAt
        );
    }

    private ToolInvocationProposal withStatusAndVersion(ToolInvocationProposal p,
                                                        ToolInvocationProposalStatus status,
                                                        int version) {
        return new ToolInvocationProposal(
                p.id(), p.proposalId(), p.requestId(), p.runId(),
                p.sessionKey(), p.userId(), p.roleCode(),
                p.toolName(), p.toolsetId(), p.argumentsCanonicalJson(), p.argumentsHash(),
                p.riskLevel(), p.targetPaths(), p.previewSummary(),
                p.workspaceDirtyAtCreate(), p.dirtyFilesAtCreate(),
                status, version,
                p.executedAt(), p.executionResult(), p.executionError(),
                p.gitHeadShaAtCreate(), p.gitBaselineSha(), p.gitCommitSha(), p.gitChangedFiles(),
                p.reviewedAt(), p.reviewReason(),
                p.createTime(), p.updateTime(), p.expiresAt()
        );
    }
}
