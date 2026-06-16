package com.springclaw.service.workspace;

import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * </ul>
 */
@Component
public class WorkspaceGitGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGitGuard.class);

    private final GitOperations git;
    private final PathNormalizer pathNormalizer;
    private final ToolInvocationProposalRepository repository;

    public WorkspaceGitGuard(GitOperations git,
                             PathNormalizer pathNormalizer,
                             ToolInvocationProposalRepository repository) {
        this.git = git;
        this.pathNormalizer = pathNormalizer;
        this.repository = repository;
    }

    /**
     * Wraps tool execution. Throws SecurityException when invariants are violated;
     * the caller (Aspect) translates that into proposal=FAILED and bubbles to the user.
     */
    public <T> T execute(ToolInvocationProposal proposal, Callable<T> toolExecution) throws Exception {
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

        if (!repository.recordBaseline(proposal.proposalId(), currentSha)) {
            throw new SecurityException(
                    "proposal 状态在 baseline 写入时已变化，拒绝执行: " + proposal.proposalId());
        }

        T result;
        try {
            result = toolExecution.call();
        } catch (Exception ex) {
            // 不变量 15：tool 抛异常 → 只 rollback targetPaths
            List<String> failedRollback = rollbackPaths(currentSha, proposal.targetPaths());
            if (!failedRollback.isEmpty()) {
                log.error("rollback after tool exception partial-failed for proposal={}, failed={}",
                        proposal.proposalId(), failedRollback);
            }
            throw ex;
        }

        Set<String> afterDirty = git.statusNameOnly().stream()
                .map(p -> pathNormalizer.normalizeRepoPath(git.workspaceRoot(), p))
                .collect(Collectors.toUnmodifiableSet());

        Set<String> newlyChanged = new HashSet<>(afterDirty);
        newlyChanged.removeAll(beforeDirty);

        Set<String> outOfScope = newlyChanged.stream()
                .filter(p -> !targetPaths.contains(p))
                .collect(Collectors.toUnmodifiableSet());

        if (!outOfScope.isEmpty()) {
            log.error("工具改动超出 targetPaths: outOfScope={}", outOfScope);
            // 不变量 15：精确清理，区分 tracked/untracked，不碰用户原 dirty
            List<String> failedTargets = rollbackPaths(currentSha, proposal.targetPaths());
            List<String> failedOut = rollbackPaths(currentSha, List.copyOf(outOfScope));
            String suffix = "";
            if (!failedTargets.isEmpty() || !failedOut.isEmpty()) {
                List<String> all = new ArrayList<>();
                all.addAll(failedTargets);
                all.addAll(failedOut);
                suffix = "；rollback 部分失败，需手工介入: " + all;
            }
            throw new SecurityException("工具改动超出授权范围: " + outOfScope + suffix);
        }

        // 不变量 14：工具成功但无实际 diff
        if (newlyChanged.isEmpty()) {
            if (!repository.recordCommit(proposal.proposalId(), null, List.of(),
                    "no-op write; nothing committed")) {
                log.error("recordCommit(no-op) returned false for proposal={}, status no longer EXECUTING",
                        proposal.proposalId());
            }
            return result;
        }

        git.add(proposal.targetPaths());
        String commitSha = git.commit(buildCommitMessage(proposal));
        if (!repository.recordCommit(proposal.proposalId(), commitSha,
                List.copyOf(newlyChanged), "ok")) {
            log.error("recordCommit returned false for proposal={} commit={}, status no longer EXECUTING — workspace already committed",
                    proposal.proposalId(), commitSha);
        }
        return result;
    }

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
