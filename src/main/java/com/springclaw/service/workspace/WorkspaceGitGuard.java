package com.springclaw.service.workspace;

import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * 写工具执行期 git 守卫：
 * <ul>
 *   <li>不变量 10：执行前 HEAD 必须等于 proposal.gitHeadShaAtCreate（baseline 一致）</li>
 *   <li>baseline 二次校验：targetPaths 仍 clean</li>
 *   <li>不变量 7：工具改动 ⊆ targetPaths（越界即 rollback + SecurityException）</li>
 *   <li>不变量 14：工具成功但 newlyChanged 为空 → EXECUTED, commitSha=null</li>
 *   <li>不变量 15：rollback 区分 tracked / untracked，不碰用户原 dirty 文件</li>
 *   <li>不变量 16：同一工作区写执行必须持有独占租约，提交前 token 必须仍有效</li>
 *   <li>不变量 17：成功终态保存真实工具结果、fencing token 与 Git 审计元数据</li>
 * </ul>
 */
@Component
public class WorkspaceGitGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGitGuard.class);

    private final GitOperations git;
    private final PathNormalizer pathNormalizer;
    private final ToolInvocationProposalRepository repository;
    private final WorkspaceMutationLeaseCoordinator leaseCoordinator;
    private final ToolExecutionResultSerializer resultSerializer;

    public WorkspaceGitGuard(GitOperations git,
                             PathNormalizer pathNormalizer,
                             ToolInvocationProposalRepository repository,
                             WorkspaceMutationLeaseCoordinator leaseCoordinator,
                             ToolExecutionResultSerializer resultSerializer) {
        this.git = git;
        this.pathNormalizer = pathNormalizer;
        this.repository = repository;
        this.leaseCoordinator = leaseCoordinator;
        this.resultSerializer = resultSerializer;
    }

    /**
     * Wraps tool execution. Throws SecurityException when invariants are violated;
     * the caller (Aspect) translates that into proposal=FAILED and bubbles to the user.
     */
    public <T> T execute(ToolInvocationProposal proposal, Callable<T> toolExecution) throws Exception {
        return leaseCoordinator.executeExclusive(
                git.workspaceRoot(),
                proposal.proposalId(),
                lease -> executeWithLease(proposal, toolExecution, lease));
    }

    private <T> T executeWithLease(ToolInvocationProposal proposal,
                                   Callable<T> toolExecution,
                                   WorkspaceMutationLease lease) throws Exception {
        String currentSha = git.headSha();

        // 不变量 10
        if (!currentSha.equals(proposal.gitHeadShaAtCreate())) {
            throw new SecurityException(
                    "工作区 HEAD 已变化（baseline=%s, current=%s），proposal 失效，请重新生成"
                            .formatted(proposal.gitHeadShaAtCreate(), currentSha));
        }

        Set<String> targetPaths = new LinkedHashSet<>(proposal.targetPaths());

        Set<String> beforeDirty = git.statusNameOnly().stream()
                .map(p -> pathNormalizer.normalizeRepoPath(git.workspaceRoot(), p))
                .collect(Collectors.toUnmodifiableSet());

        // baseline 二次校验：targetPaths 仍必须 clean
        Set<String> targetPathsDirtyNow = beforeDirty.stream()
                .filter(targetPaths::contains)
                .collect(Collectors.toUnmodifiableSet());
        if (!targetPathsDirtyNow.isEmpty()) {
            throw new SecurityException("targetPaths 在 proposal 创建后变 dirty，baseline 失效: " + targetPathsDirtyNow);
        }

        Map<String, DirtyFileSnapshot> dirtyNonTargetSnapshots = snapshotDirtyNonTargets(beforeDirty, targetPaths);

        if (!repository.recordBaseline(proposal.proposalId(), currentSha)) {
            throw new SecurityException(
                    "proposal 状态在 baseline 写入时已变化，拒绝执行: " + proposal.proposalId());
        }

        T result;
        try {
            result = toolExecution.call();
        } catch (Exception ex) {
            // 不变量 15：tool 抛异常 → rollback targetPaths，并恢复执行期间被碰过的 dirty 非目标文件
            List<String> failedRollback = rollbackPaths(currentSha, proposal.targetPaths());
            List<String> changedDirtyNonTargets = changedSnapshots(dirtyNonTargetSnapshots);
            List<String> failedDirtyRestore = restoreSnapshots(dirtyNonTargetSnapshots, changedDirtyNonTargets);
            if (!failedRollback.isEmpty() || !failedDirtyRestore.isEmpty()) {
                log.error("rollback after tool exception partial-failed for proposal={}, targetFailed={}, dirtyRestoreFailed={}",
                        proposal.proposalId(), failedRollback, failedDirtyRestore);
            }
            if (!changedDirtyNonTargets.isEmpty()) {
                log.error("tool exception path restored dirty non-target files for proposal={}, restored={}",
                        proposal.proposalId(), changedDirtyNonTargets);
            }
            throw ex;
        }

        Set<String> afterDirty = git.statusNameOnly().stream()
                .map(p -> pathNormalizer.normalizeRepoPath(git.workspaceRoot(), p))
                .collect(Collectors.toUnmodifiableSet());

        Set<String> newlyChanged = new LinkedHashSet<>(afterDirty);
        newlyChanged.removeAll(beforeDirty);

        Set<String> outOfScope = newlyChanged.stream()
                .filter(p -> !targetPaths.contains(p))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> changedDirtyNonTargets = changedSnapshots(dirtyNonTargetSnapshots);
        outOfScope.addAll(changedDirtyNonTargets);

        if (!outOfScope.isEmpty()) {
            log.error("工具改动超出 targetPaths: outOfScope={}", outOfScope);
            // 不变量 15：精确清理，区分 tracked/untracked；dirty 非目标文件恢复到工具执行前快照
            List<String> newlyOutOfScope = newlyChanged.stream()
                    .filter(p -> !targetPaths.contains(p))
                    .toList();
            List<String> failedTargets = rollbackPaths(currentSha, proposal.targetPaths());
            List<String> failedOut = rollbackPaths(currentSha, newlyOutOfScope);
            List<String> failedDirtyRestore = restoreSnapshots(dirtyNonTargetSnapshots, changedDirtyNonTargets);
            String suffix = "";
            if (!failedTargets.isEmpty() || !failedOut.isEmpty() || !failedDirtyRestore.isEmpty()) {
                List<String> all = new ArrayList<>();
                all.addAll(failedTargets);
                all.addAll(failedOut);
                all.addAll(failedDirtyRestore);
                suffix = "；rollback 部分失败，需手工介入: " + all;
            }
            throw new SecurityException("工具改动超出授权范围: " + outOfScope + suffix);
        }

        // The coordinator holds SELECT ... FOR UPDATE for the full callback. This check and
        // the following Git publication/terminal write therefore cannot be overtaken by a
        // higher fencing token. If ownership is uncertain, stop without mutating the workspace.
        leaseCoordinator.assertCurrent(lease);

        // 不变量 14：工具成功但无实际 diff
        if (newlyChanged.isEmpty()) {
            String executionResult = resultSerializer.serialize(
                    proposal, result, lease.fencingToken(), null, List.of(), true);
            if (!repository.recordCommit(proposal.proposalId(), null, List.of(),
                    executionResult)) {
                log.error("recordCommit(no-op) returned false for proposal={}, status no longer EXECUTING",
                        proposal.proposalId());
            }
            return result;
        }

        git.add(proposal.targetPaths());
        String commitSha = git.commit(buildCommitMessage(proposal));
        List<String> changedFiles = List.copyOf(newlyChanged);
        String executionResult = resultSerializer.serialize(
                proposal, result, lease.fencingToken(), commitSha, changedFiles, false);
        if (!repository.recordCommit(proposal.proposalId(), commitSha,
                changedFiles, executionResult)) {
            log.error("recordCommit returned false for proposal={} commit={}, status no longer EXECUTING — workspace already committed",
                    proposal.proposalId(), commitSha);
        }
        return result;
    }

    private record DirtyFileSnapshot(boolean exists, byte[] content) { }

    /**
     * Try to rollback each path. Returns the list of paths that failed; never throws.
     * Caller decides how to surface partial failures.
     */
    private List<String> rollbackPaths(String sha, List<String> paths) {
        List<String> failed = new ArrayList<>();
        for (String path : paths) {
            try {
                if (git.isTracked(path)) {
                    git.checkoutFromSha(sha, path);
                } else {
                    git.deleteFile(path);
                }
            } catch (RuntimeException ex) {
                log.error("rollback path {} failed: {}", path, ex.getMessage());
                failed.add(path);
            }
        }
        return failed;
    }

    private Map<String, DirtyFileSnapshot> snapshotDirtyNonTargets(Set<String> beforeDirty, Set<String> targetPaths) {
        Map<String, DirtyFileSnapshot> snapshots = new LinkedHashMap<>();
        for (String path : beforeDirty) {
            if (!targetPaths.contains(path)) {
                Path absolutePath = git.workspaceRoot().resolve(path);
                if (Files.isDirectory(absolutePath)) {
                    continue;
                }
                snapshots.put(path, snapshotPath(path));
            }
        }
        return snapshots;
    }

    private DirtyFileSnapshot snapshotPath(String path) {
        Path absolutePath = git.workspaceRoot().resolve(path);
        try {
            if (!Files.exists(absolutePath)) {
                return new DirtyFileSnapshot(false, null);
            }
            return new DirtyFileSnapshot(true, Files.readAllBytes(absolutePath));
        } catch (IOException ex) {
            throw new SecurityException("dirty 文件快照读取失败: " + path, ex);
        }
    }

    private List<String> changedSnapshots(Map<String, DirtyFileSnapshot> before) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, DirtyFileSnapshot> entry : before.entrySet()) {
            DirtyFileSnapshot current = snapshotPath(entry.getKey());
            DirtyFileSnapshot previous = entry.getValue();
            boolean existsChanged = previous.exists() != current.exists();
            boolean contentChanged = previous.exists()
                    && current.exists()
                    && !java.util.Arrays.equals(previous.content(), current.content());
            if (existsChanged || contentChanged) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    private List<String> restoreSnapshots(Map<String, DirtyFileSnapshot> snapshots, List<String> paths) {
        List<String> failed = new ArrayList<>();
        for (String path : paths) {
            DirtyFileSnapshot snapshot = snapshots.get(path);
            if (snapshot == null) {
                continue;
            }
            Path absolutePath = git.workspaceRoot().resolve(path);
            try {
                if (!snapshot.exists()) {
                    Files.deleteIfExists(absolutePath);
                } else {
                    Path parent = absolutePath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(absolutePath, snapshot.content());
                }
            } catch (IOException ex) {
                log.error("restore dirty snapshot {} failed: {}", path, ex.getMessage());
                failed.add(path);
            }
        }
        return failed;
    }

    private String buildCommitMessage(ToolInvocationProposal p) {
        String preview = p.previewSummary() == null ? p.toolName() : p.previewSummary();
        String runId = p.runId() == null ? "" : p.runId();
        String requestId = p.requestId() == null ? "" : p.requestId();
        String userId = p.userId() == null ? "" : p.userId();
        String argsHash = p.argumentsHash() == null ? "" : p.argumentsHash();
        String baselineSha = p.gitHeadShaAtCreate() == null ? "" : p.gitHeadShaAtCreate();
        String targetList = String.join(", ", p.targetPaths());

        StringBuilder sb = new StringBuilder(512);
        sb.append("[agent-write] ").append(preview).append("\n\n");
        sb.append("proposalId: ").append(p.proposalId()).append('\n');
        sb.append("requestId: ").append(requestId).append('\n');
        sb.append("runId: ").append(runId).append('\n');
        sb.append("tool: ").append(p.toolName()).append('\n');
        sb.append("user: ").append(userId).append('\n');
        sb.append("argsHash: ").append(argsHash).append('\n');
        sb.append("baselineSha: ").append(baselineSha).append('\n');
        sb.append("targetPaths: ").append(targetList).append('\n');
        return sb.toString();
    }
}
