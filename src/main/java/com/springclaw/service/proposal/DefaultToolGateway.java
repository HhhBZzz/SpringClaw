package com.springclaw.service.proposal;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Orchestrates the durable runtime tool-proposal lifecycle without bypassing
 * the existing Aspect-protected {@link ToolInvoker} execution path.
 */
@Component
public class DefaultToolGateway implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolGateway.class);

    private final ToolInvocationProposalService proposalService;
    private final ToolInvoker toolInvoker;
    private final RunLifecycleObserver lifecycleObserver;

    public DefaultToolGateway(ToolInvocationProposalService proposalService,
                              ToolInvoker toolInvoker,
                              RunLifecycleObserver lifecycleObserver) {
        this.proposalService = proposalService;
        this.toolInvoker = toolInvoker;
        this.lifecycleObserver = lifecycleObserver;
    }

    @Override
    public ToolInvocationProposal requestApproval(ToolInvocationSnapshot snapshot,
                                                   ToolExecutionContext context) {
        return proposalService.createPending(snapshot, context);
    }

    @Override
    public ToolInvocationProposal confirm(String proposalId, String reason) {
        return proposalService.confirm(proposalId, reason);
    }

    @Override
    public void resume(String proposalId) {
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
        ToolExecutionContext context = new ToolExecutionContext(
                proposal.sessionKey(), "api", proposal.userId(), proposal.requestId(),
                "proposal-execution", proposal.runId(), proposal.roleCode()
        );
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            ToolExecutionContextHolder.setApprovedProposal(ApprovedProposalContext.from(proposal));
            try {
                toolInvoker.invoke(proposal.toolName(), proposal.argumentsCanonicalJson());
                projectToolSucceeded(proposal);
            } catch (Throwable ex) {
                proposalService.markFailed(proposalId, failureDetail(ex));
                projectToolFailed(proposal, ex);
                log.error("execute proposal {} failed", proposalId, ex);
            } finally {
                ToolExecutionContextHolder.clearApprovedProposal();
            }
        }
    }

    private void projectConfirmationApproved(ToolInvocationProposal proposal) {
        if (!hasRun(proposal)) {
            return;
        }
        try {
            lifecycleObserver.confirmationApproved(proposal.runId(), Instant.now());
            lifecycleObserver.toolStarted(proposal.runId(), Instant.now());
        } catch (RuntimeException ex) {
            log.warn("canonical confirmationApproved/toolStarted projection failed, proposalId={}, reason={}",
                    proposal.proposalId(), ex.getMessage());
        }
    }

    private void projectToolSucceeded(ToolInvocationProposal proposal) {
        if (!hasRun(proposal)) {
            return;
        }
        try {
            lifecycleObserver.toolSucceeded(proposal.runId(), Instant.now());
        } catch (RuntimeException ex) {
            log.warn("canonical toolSucceeded projection failed, proposalId={}, reason={}",
                    proposal.proposalId(), ex.getMessage());
        }
    }

    private void projectToolFailed(ToolInvocationProposal proposal, Throwable error) {
        if (!hasRun(proposal)) {
            return;
        }
        try {
            lifecycleObserver.toolFailed(proposal.runId(), Instant.now());
        } catch (RuntimeException ex) {
            log.warn("canonical toolFailed observation failed, proposalId={}, reason={}",
                    proposal.proposalId(), ex.getMessage());
        }
        try {
            lifecycleObserver.failed(proposal.runId(), "TOOL_EXECUTION_FAILED", error, Instant.now());
        } catch (RuntimeException ex) {
            log.warn("canonical TOOL_EXECUTION_FAILED projection failed, proposalId={}, reason={}",
                    proposal.proposalId(), ex.getMessage());
        }
    }

    private static boolean hasRun(ToolInvocationProposal proposal) {
        return proposal.runId() != null && !proposal.runId().isBlank();
    }

    private static String failureDetail(Throwable error) {
        return error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "" : error.getMessage());
    }
}
