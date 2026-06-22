package com.springclaw.service.proposal;

import com.springclaw.runtime.bridge.LegacyLifecycleObserver;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * confirm 事务提交后异步执行 proposal 的工具调用：
 * <ol>
 *   <li>设置 ApprovedProposalContext 到 ThreadLocal</li>
 *   <li>调用 ToolInvoker.invoke(...) — 触发 ToolRuntimeAspect 的二次校验路径（不变量 11）</li>
 *   <li>清理 ThreadLocal</li>
 * </ol>
 */
@Component
public class ToolProposalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolProposalExecutionService.class);

    private final ToolInvocationProposalService proposalService;
    private final ToolInvoker toolInvoker;
    private final LegacyLifecycleObserver lifecycleObserver;

    public ToolProposalExecutionService(ToolInvocationProposalService proposalService,
                                        ToolInvoker toolInvoker,
                                        LegacyLifecycleObserver lifecycleObserver) {
        this.proposalService = proposalService;
        this.toolInvoker = toolInvoker;
        this.lifecycleObserver = lifecycleObserver;
    }

    @Async("proposalExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExecutionRequested(ToolProposalExecutionRequestedEvent event) {
        String proposalId = event.proposalId();
        ToolInvocationProposal proposal = proposalService.findByProposalId(proposalId).orElse(null);
        if (proposal == null) {
            log.warn("ToolInvocationProposal {} 不存在，跳过执行", proposalId);
            return;
        }
        if (proposal.status() != ToolInvocationProposalStatus.EXECUTING) {
            log.warn("ToolInvocationProposal {} 状态非 EXECUTING（{}），跳过", proposalId, proposal.status());
            return;
        }

        projectConfirmationApproved(proposal);

        // 在 async 线程上重建 ToolExecutionContext —— 否则 ToolRuntimeAspect 的权限检查、
        // rate limit、audit 都会以 null context 运行，丢失 session/user/request 元数据，
        // 也可能让 toolPermissionService 把 approved proposal 的发起人当作匿名用户而拒绝。
        ToolExecutionContext executionContext = new ToolExecutionContext(
                proposal.sessionKey(),
                "api",                              // confirm 是 REST API 路径
                proposal.userId(),
                proposal.requestId(),
                "proposal-execution",               // phase 标识便于审计区分
                proposal.runId(),
                proposal.roleCode()
        );

        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(executionContext)) {
            ToolExecutionContextHolder.setApprovedProposal(ApprovedProposalContext.from(proposal));
            try {
                // 调用代理 bean → 再次进入 ToolRuntimeAspect → 二次校验 + GitGuard 包裹工具执行
                toolInvoker.invoke(proposal.toolName(), proposal.argumentsCanonicalJson());
            } catch (Throwable ex) {
                // ToolRuntimeAspect 在校验失败时已经调过 markFailed；这里兜底再做一次（幂等）
                proposalService.markFailed(proposalId,
                        ex.getClass().getSimpleName() + ": "
                                + (ex.getMessage() == null ? "" : ex.getMessage()));
                log.error("execute proposal {} failed", proposalId, ex);
            } finally {
                ToolExecutionContextHolder.clearApprovedProposal();
            }
        }
    }

    private void projectConfirmationApproved(ToolInvocationProposal proposal) {
        String runId = proposal.runId();
        if (runId == null || runId.isBlank() || lifecycleObserver == null) {
            return;
        }
        try {
            lifecycleObserver.confirmationApproved(runId, Instant.now());
            lifecycleObserver.toolStarted(runId, Instant.now());
        } catch (RuntimeException ex) {
            log.warn("canonical confirmationApproved/toolStarted projection failed, proposalId={}, reason={}",
                    proposal.proposalId(), ex.getMessage());
        }
    }
}