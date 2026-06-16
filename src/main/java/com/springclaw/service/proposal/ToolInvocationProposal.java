package com.springclaw.service.proposal;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具调用授权单（不可变值对象）。
 *
 * 一表合一存储：snapshot（参数+目标+风险）+ 状态机 + git 元数据。
 * 集合字段在构造期 deep-copy + List.copyOf，保证记录的不可变语义。
 */
public record ToolInvocationProposal(
        Long id,
        String proposalId,
        String requestId,
        String runId,
        String sessionKey,
        String userId,
        String roleCode,
        String toolName,
        String toolsetId,
        String argumentsCanonicalJson,
        String argumentsHash,
        String riskLevel,
        List<String> targetPaths,
        String previewSummary,
        boolean workspaceDirtyAtCreate,
        List<String> dirtyFilesAtCreate,
        ToolInvocationProposalStatus status,
        int version,
        LocalDateTime executedAt,
        String executionResult,
        String executionError,
        String gitHeadShaAtCreate,
        String gitBaselineSha,
        String gitCommitSha,
        List<String> gitChangedFiles,
        LocalDateTime reviewedAt,
        String reviewReason,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime expiresAt
) {
    public ToolInvocationProposal {
        targetPaths = targetPaths == null ? List.of() : List.copyOf(targetPaths);
        dirtyFilesAtCreate = dirtyFilesAtCreate == null ? List.of() : List.copyOf(dirtyFilesAtCreate);
        gitChangedFiles = gitChangedFiles == null ? List.of() : List.copyOf(gitChangedFiles);
    }
}
