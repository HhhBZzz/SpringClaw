package com.springclaw.service.task;

/**
 * 聊天侧定时任务草稿。
 */
public record TaskCreationDraft(String name,
                                String scheduleType,
                                String scheduleExpression,
                                String scheduleLabel,
                                String targetType,
                                String targetRef,
                                String inputPayload,
                                String channel,
                                String deliveryMode,
                                String deliveryTarget,
                                boolean persistToSession,
                                String sessionKeyTemplate,
                                String summary) {
}
