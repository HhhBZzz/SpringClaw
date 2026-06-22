package com.springclaw.service.proposal;

/**
 * createPending 事务提交后发布的事件，由 {@link ToolProposalLifecycleListener}
 * 在 AFTER_COMMIT 消费，调用 {@code LegacyLifecycleObserver.confirmationRequired}
 * 将持久化的工具授权单挂起投影为 canonical WAITING_CONFIRMATION。
 *
 * <p>该监听器是持久化工具授权单挂起的唯一所有者——Task 6 的 PendingToolApproval
 * 渲染路径不得再次调用 confirmationRequired。
 */
public record ToolProposalCreatedEvent(String proposalId, String runId) {}
