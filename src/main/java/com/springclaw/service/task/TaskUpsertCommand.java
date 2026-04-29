package com.springclaw.service.task;

/**
 * 任务创建/更新命令。
 */
public record TaskUpsertCommand(String name,
                                Boolean enabled,
                                String scheduleType,
                                String scheduleExpression,
                                String targetType,
                                String targetRef,
                                String inputPayload,
                                String channel,
                                String deliveryMode,
                                String deliveryTarget,
                                Boolean persistToSession,
                                String sessionKeyTemplate) {
}
