package com.springclaw.service.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceMutationLeaseCoordinatorTest {

    @TempDir
    Path workspaceRoot;

    private WorkspaceIdentity identity;
    private WorkspaceMutationLeaseRepository repository;
    private WorkspaceMutationLeaseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        identity = mock(WorkspaceIdentity.class);
        repository = mock(WorkspaceMutationLeaseRepository.class);
        coordinator = new WorkspaceMutationLeaseCoordinator(identity, repository, 300, 30);
        when(identity.id(workspaceRoot)).thenReturn("workspace-id");
    }

    @Test
    void acquireUsesWorkspaceIdentityAndConfiguredExecutionTtl() {
        WorkspaceMutationLease lease = lease();
        when(repository.acquire("workspace-id", "proposal-1", Duration.ofSeconds(300)))
                .thenReturn(Optional.of(lease));

        assertThat(coordinator.acquire(workspaceRoot, "proposal-1")).isSameAs(lease);
    }

    @Test
    void acquireRejectsAnActiveWorkspaceLease() {
        when(repository.acquire("workspace-id", "proposal-2", Duration.ofSeconds(300)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.acquire(workspaceRoot, "proposal-2"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("工作区正在被其他写任务占用");
    }

    @Test
    void renewForCommitRejectsAStaleFencingToken() {
        WorkspaceMutationLease lease = lease();
        when(repository.renewForCommit(
                "workspace-id", "proposal-1", 7L, Duration.ofSeconds(30))).thenReturn(false);

        assertThatThrownBy(() -> coordinator.renewForCommit(lease))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("fencing token 已失效");
    }

    @Test
    void releaseUsesTheExactHolderAndFencingToken() {
        WorkspaceMutationLease lease = lease();

        coordinator.release(lease);

        verify(repository).release("workspace-id", "proposal-1", 7L);
    }

    private WorkspaceMutationLease lease() {
        return new WorkspaceMutationLease(
                "workspace-id", "proposal-1", 7L, LocalDateTime.now().plusMinutes(5));
    }
}
