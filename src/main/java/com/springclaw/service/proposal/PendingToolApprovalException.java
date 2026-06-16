package com.springclaw.service.proposal;

/**
 * 用户审批未完成时由 ToolRuntimeAspect 抛出，被 ChatServiceImpl 捕获后转 SSE 事件。
 *
 * 仅携带 proposalId，由上层根据 id 反查授权单并下发给前端。
 */
public class PendingToolApprovalException extends RuntimeException {

    private final String proposalId;

    public PendingToolApprovalException(String proposalId) {
        super("tool invocation requires user approval: " + proposalId);
        this.proposalId = proposalId;
    }

    public String proposalId() {
        return proposalId;
    }
}
