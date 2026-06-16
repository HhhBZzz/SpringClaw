package com.springclaw.service.proposal;

import java.util.List;
import java.util.Set;

/**
 * 工具调用现场快照——授权单创建时锁定的最小可重放上下文。
 *
 * 在 ProposalService.create 时从 SnapshotService 装配，
 * 在 confirm-resume 终态校验时与当前现场对比，差异即拒绝。
 */
public record ToolInvocationSnapshot(
        String toolName,
        String toolsetId,
        String argumentsCanonicalJson,
        String argumentsHash,
        String riskLevel,
        List<String> targetPaths,
        String previewSummary,
        boolean workspaceDirty,
        Set<String> dirtyFilesAtCreate,
        String gitHeadShaAtCreate
) {
    public ToolInvocationSnapshot {
        targetPaths = targetPaths == null ? List.of() : List.copyOf(targetPaths);
        dirtyFilesAtCreate = dirtyFilesAtCreate == null ? Set.of() : Set.copyOf(dirtyFilesAtCreate);
    }
}
