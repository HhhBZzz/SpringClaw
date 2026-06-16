package com.springclaw.service.proposal;

/**
 * 工具调用授权单状态机。
 *
 * 合法迁移（参考 P0 设计）：
 *   PENDING -> APPROVED | REJECTED | EXPIRED | CANCELLED
 *   APPROVED -> EXECUTING
 *   EXECUTING -> EXECUTED | FAILED
 */
public enum ToolInvocationProposalStatus {
    PENDING,
    APPROVED,
    EXECUTING,
    EXECUTED,
    FAILED,
    REJECTED,
    EXPIRED,
    CANCELLED;

    public boolean isTerminal() {
        return this == EXECUTED || this == REJECTED || this == EXPIRED
                || this == CANCELLED || this == FAILED;
    }
}
