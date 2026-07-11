package com.springclaw.service.proposal;

import com.springclaw.tool.runtime.ToolExecutionContext;

/**
 * Single runtime boundary for write-tool authorization, confirmation, and resume.
 */
public interface ToolGateway {

    ToolInvocationProposal requestApproval(
            ToolInvocationSnapshot snapshot,
            ToolExecutionContext context
    );

    ToolInvocationProposal confirm(String proposalId, String reason);

    void resume(String proposalId);
}
