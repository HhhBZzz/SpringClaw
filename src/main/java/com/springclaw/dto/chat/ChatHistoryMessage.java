package com.springclaw.dto.chat;

/**
 * 前端聊天页可直接渲染的历史消息。
 */
public record ChatHistoryMessage(
        String id,
        String role,
        String content,
        String model,
        long createdAt
) {
}
