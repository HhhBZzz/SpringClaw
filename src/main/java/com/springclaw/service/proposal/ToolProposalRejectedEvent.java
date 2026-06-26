package com.springclaw.service.proposal;

/**
 * reject 事务提交后发布的事件，由 {@link ToolProposalLifecycleListener}
 * 在 AFTER_COMMIT 消费，调用 {@code RunLifecycleObserver.confirmationRejected}
 * 将用户拒绝投影为 canonical FAILED。
 */
public record ToolProposalRejectedEvent(String proposalId, String runId, String reason) {}
