package com.springclaw.scheduled;

import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolProposalCleanupTaskTest {

    private final ToolInvocationProposalRepository repository = mock(ToolInvocationProposalRepository.class);
    private final ToolInvocationProposalService proposalService = mock(ToolInvocationProposalService.class);
    private final ToolProposalCleanupTask cleanupTask = new ToolProposalCleanupTask(repository, proposalService);

    @Test
    void cleanup_expiresPendingAndMarksStuckExecutingAsFailed() {
        when(repository.expirePendingBefore(any(LocalDateTime.class))).thenReturn(2);
        when(repository.findStuckExecuting(any(LocalDateTime.class))).thenReturn(List.of(
                proposal("tip-1"),
                proposal("tip-2")
        ));

        cleanupTask.cleanup();

        verify(repository).expirePendingBefore(any(LocalDateTime.class));
        verify(repository).findStuckExecuting(any(LocalDateTime.class));
        verify(proposalService).markFailed("tip-1", "execution interrupted or timeout");
        verify(proposalService).markFailed("tip-2", "execution interrupted or timeout");
    }

    @Test
    void cleanup_noStaleProposals_isNoop() {
        when(repository.expirePendingBefore(any(LocalDateTime.class))).thenReturn(0);
        when(repository.findStuckExecuting(any(LocalDateTime.class))).thenReturn(List.of());

        cleanupTask.cleanup();

        verify(repository).expirePendingBefore(any(LocalDateTime.class));
        verify(repository).findStuckExecuting(any(LocalDateTime.class));
        verify(proposalService, never()).markFailed(any(), any());
    }

    @Test
    void cleanup_continuesWhenOneMarkFailedThrows() {
        when(repository.expirePendingBefore(any(LocalDateTime.class))).thenReturn(0);
        when(repository.findStuckExecuting(any(LocalDateTime.class))).thenReturn(List.of(
                proposal("tip-1"),
                proposal("tip-2")
        ));
        doThrow(new RuntimeException("boom"))
                .when(proposalService).markFailed("tip-1", "execution interrupted or timeout");

        assertThatCode(cleanupTask::cleanup).doesNotThrowAnyException();

        verify(proposalService).markFailed("tip-1", "execution interrupted or timeout");
        verify(proposalService).markFailed("tip-2", "execution interrupted or timeout");
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
                ToolInvocationProposalStatus.EXECUTING,
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
                now.minusMinutes(20),
                now.minusMinutes(20),
                now.plusMinutes(15)
        );
    }
}
