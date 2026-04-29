package com.springclaw.service.task;

/**
 * 单次任务执行结果。
 */
public record TaskExecutionOutcome(String summary,
                                   String resultPayload,
                                   String requestId,
                                   String sessionKey) {
}
