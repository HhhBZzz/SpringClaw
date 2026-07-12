package com.springclaw.service.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Duration;

/** Coordinates workspace identity, execution leases, and commit fencing. */
@Component
public class WorkspaceMutationLeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMutationLeaseCoordinator.class);

    private final WorkspaceIdentity identity;
    private final WorkspaceMutationLeaseRepository repository;
    private final Duration executionTtl;

    public WorkspaceMutationLeaseCoordinator(
            WorkspaceIdentity identity,
            WorkspaceMutationLeaseRepository repository,
            @Value("${springclaw.workspace.mutation-lease-seconds:300}") long executionLeaseSeconds) {
        if (executionLeaseSeconds <= 0) {
            throw new IllegalArgumentException("工作区租约秒数必须大于 0");
        }
        this.identity = identity;
        this.repository = repository;
        this.executionTtl = Duration.ofSeconds(executionLeaseSeconds);
    }

    @Transactional(rollbackFor = Exception.class)
    public <T> T executeExclusive(Path workspaceRoot,
                                  String proposalId,
                                  LeaseWork<T> work) throws Exception {
        String workspaceId = identity.id(workspaceRoot);
        WorkspaceMutationLease lease = repository.acquire(workspaceId, proposalId, executionTtl)
                .orElseThrow(() -> new SecurityException(
                        "工作区正在被其他写任务占用，拒绝并发执行: " + workspaceId));
        try {
            return work.execute(lease);
        } finally {
            boolean released = repository.release(
                    lease.workspaceId(), lease.proposalId(), lease.fencingToken());
            if (!released) {
                log.warn("workspace lease release did not match current holder: workspace={}, proposal={}, token={}",
                        lease.workspaceId(), lease.proposalId(), lease.fencingToken());
            }
        }
    }

    public void assertCurrent(WorkspaceMutationLease lease) {
        if (!repository.isCurrent(
                lease.workspaceId(), lease.proposalId(), lease.fencingToken())) {
            throw new SecurityException(
                    "工作区写租约 fencing token 已失效，拒绝发布: " + lease.fencingToken());
        }
    }

    @FunctionalInterface
    public interface LeaseWork<T> {
        T execute(WorkspaceMutationLease lease) throws Exception;
    }
}
