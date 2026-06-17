package com.springclaw.service.proposal;

/**
 * confirm 事务提交后发布的事件，由 ToolProposalExecutionService 在 AFTER_COMMIT 异步消费。
 * 命名反映目标状态（EXECUTING）—— 不变量 16。
 */
public record ToolProposalExecutionRequestedEvent(String proposalId) {}