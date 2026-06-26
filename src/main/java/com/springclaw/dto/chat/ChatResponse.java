package com.springclaw.dto.chat;

/**
 * 对话响应。
 */
public record ChatResponse(
        String requestId,
        String sessionKey,
        String answer,
        String model,
        long timestamp
) {
}
