package com.springclaw.service.event;

import java.time.Instant;

/**
 * 消息事件写入收据（Phase 3A1 Task 5）。同一 eventKey 重复 append 返回同一 eventId。
 */
public record MessageEventReceipt(
        long eventId,
        String eventKey,
        Instant occurredAt
) {
}
