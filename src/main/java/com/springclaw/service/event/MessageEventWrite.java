package com.springclaw.service.event;

/**
 * 显式消息事件写入请求（Phase 3A1 Task 5）。
 *
 * <p>eventKey 是稳定收据键：memory-eligible CHAT turn 使用
 * {@code chat:<requestId>:user} / {@code chat:<requestId>:assistant:terminal} /
 * {@code chat:<requestId>:suspension}；非 memory 事件由 {@code recordSingle} 生成唯一键。
 */
public record MessageEventWrite(
        String eventKey,
        String sessionKey,
        String channel,
        String userId,
        String role,
        String eventType,
        String content,
        String requestId
) {
    public MessageEventWrite {
        if (eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException("eventKey must not be blank");
        }
    }
}
