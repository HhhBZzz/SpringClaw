package com.springclaw.service.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

/** Coordinates workspace identity, execution leases, and commit fencing. */
@Component
public class WorkspaceMutationLeaseCoordinator {

    private final WorkspaceIdentity identity;
    private final WorkspaceMutationLeaseRepository repository;
    private final Duration executionTtl;
    private final Duration commitTtl;

    public WorkspaceMutationLeaseCoordinator(
            WorkspaceIdentity identity,
            WorkspaceMutationLeaseRepository repository,
            @Value("${springclaw.workspace.mutation-lease-seconds:300}") long executionLeaseSeconds,
            @Value("${springclaw.workspace.commit-lease-seconds:30}") long commitLeaseSeconds) {
        if (executionLeaseSeconds <= 0 || commitLeaseSeconds <= 0) {
            throw new IllegalArgumentException("工作区租约秒数必须大于 0");
        }
        this.identity = identity;
        this.repository = repository;
        this.executionTtl = Duration.ofSeconds(executionLeaseSeconds);
        this.commitTtl = Duration.ofSeconds(commitLeaseSeconds);
    }

    public WorkspaceMutationLease acquire(Path workspaceRoot, String proposalId) {
        String workspaceId = identity.id(workspaceRoot);
        return repository.acquire(workspaceId, proposalId, executionTtl)
                .orElseThrow(() -> new SecurityException(
                        "工作区正在被其他写任务占用，拒绝并发执行: " + workspaceId));
    }

    public void renewForCommit(WorkspaceMutationLease lease) {
        boolean renewed = repository.renewForCommit(
                lease.workspaceId(), lease.proposalId(), lease.fencingToken(), commitTtl);
        if (!renewed) {
            throw new SecurityException(
                    "工作区写租约 fencing token 已失效，拒绝提交: " + lease.fencingToken());
        }
    }

    public void release(WorkspaceMutationLease lease) {
        repository.release(lease.workspaceId(), lease.proposalId(), lease.fencingToken());
    }
}
