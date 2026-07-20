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
    private WorkspaceFencingTokenAllocator tokenAllocator;
    private WorkspaceMutationLeaseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        identity = mock(WorkspaceIdentity.class);
        repository = mock(WorkspaceMutationLeaseRepository.class);
        tokenAllocator = mock(WorkspaceFencingTokenAllocator.class);
        coordinator = new WorkspaceMutationLeaseCoordinator(identity, repository, tokenAllocator, 300);
        when(identity.id(workspaceRoot)).thenReturn("workspace-id");
        when(tokenAllocator.nextToken("workspace-id", "proposal-1")).thenReturn(7L);
        when(repository.release("workspace-id", "proposal-1", 7L)).thenReturn(true);
    }

    @Test
    void executeExclusiveUsesWorkspaceIdentityConfiguredTtlAndExactReleaseToken() throws Exception {
        WorkspaceMutationLease lease = lease();
        when(repository.acquire("workspace-id", "proposal-1", 7L, Duration.ofSeconds(300)))
                .thenReturn(Optional.of(lease));

        String result = coordinator.executeExclusive(
                workspaceRoot, "proposal-1", acquired -> {
                    assertThat(acquired).isSameAs(lease);
                    return "done";
                });

        assertThat(result).isEqualTo("done");
        verify(repository).release("workspace-id", "proposal-1", 7L);
    }

    @Test
    void acquireRejectsAnActiveWorkspaceLease() {
        when(tokenAllocator.nextToken("workspace-id", "proposal-2")).thenReturn(8L);
        when(repository.acquire("workspace-id", "proposal-2", 8L, Duration.ofSeconds(300)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.executeExclusive(
                workspaceRoot, "proposal-2", lease -> "never"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("工作区正在被其他写任务占用");
    }

    @Test
    void assertCurrentRejectsAStaleFencingToken() {
        WorkspaceMutationLease lease = lease();
        when(repository.isCurrent("workspace-id", "proposal-1", 7L)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.assertCurrent(lease))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("fencing token 已失效");
    }

    private WorkspaceMutationLease lease() {
        return new WorkspaceMutationLease(
                "workspace-id", "proposal-1", 7L, LocalDateTime.now().plusMinutes(5));
    }
}
