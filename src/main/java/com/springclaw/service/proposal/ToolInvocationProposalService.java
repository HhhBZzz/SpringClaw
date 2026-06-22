package com.springclaw.service.proposal;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具调用授权单业务服务（完整版）。
 *
 * <p>状态机：
 * <ul>
 *   <li>createPending → PENDING</li>
 *   <li>confirm: PENDING → APPROVED → EXECUTING（事务内一气呵成，不变量 16）</li>
 *   <li>reject: PENDING → REJECTED</li>
 *   <li>markFailed: 任意非终态 → FAILED（不变量 9：不重试，需新建 proposal）</li>
 * </ul>
 *
 * <p>confirm 事务提交后由 ToolProposalExecutionService 异步消费 ToolProposalExecutionRequestedEvent
 * 调用 ToolInvoker，再次进入 ToolRuntimeAspect 的二次校验路径（不变量 11 执行侧）。
 */
@Service
public class ToolInvocationProposalService {

    private static final long DEFAULT_TTL_MINUTES = 15L;

    private final ToolInvocationProposalRepository repository;
    private final ApplicationEventPublisher publisher;

    public ToolInvocationProposalService(ToolInvocationProposalRepository repository,
                                         ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Transactional
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
        ToolInvocationProposal inserted = repository.insert(p);
        publisher.publishEvent(new ToolProposalCreatedEvent(proposalId, ctx.runId()));
        return inserted;
    }

    public Optional<ToolInvocationProposal> findByProposalId(String proposalId) {
        return repository.findByProposalId(proposalId);
    }

    public void markFailed(String proposalId, String error) {
        repository.recordFailure(proposalId, error);
    }

    /**
     * 用户确认：事务内 PENDING → APPROVED → EXECUTING（不变量 16），
     * 提交后异步触发 ToolInvoker。
     *
     * @return 状态已迁移到 EXECUTING 的最新 proposal 行
     */
    @Transactional
    public ToolInvocationProposal confirm(String proposalId, String reason) {
        ToolInvocationProposal proposal = repository.findByProposalId(proposalId)
                .orElseThrow(() -> new BusinessException(40404, "proposal 不存在"));
        if (proposal.status() != ToolInvocationProposalStatus.PENDING) {
            throw new BusinessException(40409, "状态非法: " + proposal.status());
        }
        if (proposal.expiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(40410, "已过期，请重新发起");
        }

        boolean ok1 = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                ToolInvocationProposalStatus.APPROVED,
                proposal.version(),
                reason);
        if (!ok1) {
            throw new BusinessException(40409, "状态变更失败（并发或已处理）");
        }

        boolean ok2 = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.APPROVED,
                ToolInvocationProposalStatus.EXECUTING,
                proposal.version() + 1,
                null);
        if (!ok2) {
            throw new BusinessException(40409, "进入执行状态失败");
        }

        publisher.publishEvent(new ToolProposalExecutionRequestedEvent(proposalId));
        return repository.findByProposalId(proposalId).orElseThrow();
    }

    @Transactional
    public ToolInvocationProposal reject(String proposalId, String reason) {
        ToolInvocationProposal proposal = repository.findByProposalId(proposalId)
                .orElseThrow(() -> new BusinessException(40404, "proposal 不存在"));
        if (proposal.status() != ToolInvocationProposalStatus.PENDING) {
            throw new BusinessException(40409, "状态非法: " + proposal.status());
        }
        boolean ok = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                ToolInvocationProposalStatus.REJECTED,
                proposal.version(),
                reason);
        if (!ok) {
            throw new BusinessException(40409, "状态变更失败");
        }
        publisher.publishEvent(new ToolProposalRejectedEvent(proposalId, proposal.runId(), reason));
        return repository.findByProposalId(proposalId).orElseThrow();
    }
}