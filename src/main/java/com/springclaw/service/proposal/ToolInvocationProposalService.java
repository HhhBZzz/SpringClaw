package com.springclaw.service.proposal;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具调用授权单业务服务。
 *
 * <p>P0 Task 6 阶段只暴露 ToolRuntimeAspect 需要的三个方法：
 * createPending（创建 PENDING）、findByProposalId（二次校验回查）、markFailed（终态写入）。
 * Task 7 会扩展 confirm/reject 状态机和 AfterCommit 异步执行器。
 */
@Service
public class ToolInvocationProposalService {

    private static final long DEFAULT_TTL_MINUTES = 15L;

    private final ToolInvocationProposalRepository repository;

    public ToolInvocationProposalService(ToolInvocationProposalRepository repository) {
        this.repository = repository;
    }

    public ToolInvocationProposal createPending(ToolInvocationSnapshot snapshot,
                                                ToolExecutionContext ctx) {
        if (snapshot == null) {
            throw new BusinessException(40089, "snapshot 不能为空");
        }
        if (ctx == null) {
            throw new BusinessException(40089, "ToolExecutionContext 不能为空");
        }
        String proposalId = "tip-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        ToolInvocationProposal p = new ToolInvocationProposal(
                null, proposalId,
                ctx.requestId() == null ? "" : ctx.requestId(),
                ctx.runId(),
                ctx.sessionKey() == null ? "" : ctx.sessionKey(),
                ctx.userId() == null ? "" : ctx.userId(),
                ctx.roleCode(),
                snapshot.toolName(), snapshot.toolsetId(),
                snapshot.argumentsCanonicalJson(), snapshot.argumentsHash(),
                snapshot.riskLevel(), snapshot.targetPaths(), snapshot.previewSummary(),
                snapshot.workspaceDirty(), List.copyOf(snapshot.dirtyFilesAtCreate()),
                ToolInvocationProposalStatus.PENDING, 0,
                null, null, null,
                snapshot.gitHeadShaAtCreate(), null, null, List.of(),
                null, null,
                now, now, now.plusMinutes(DEFAULT_TTL_MINUTES)
        );
        return repository.insert(p);
    }

    public Optional<ToolInvocationProposal> findByProposalId(String proposalId) {
        return repository.findByProposalId(proposalId);
    }

    public void markFailed(String proposalId, String error) {
        repository.recordFailure(proposalId, error);
    }
}
