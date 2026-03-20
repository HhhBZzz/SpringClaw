package com.openclaw.service.chat.async;

public record AsyncChatResultPayload(
        String requestId,
        String status,
        String sessionKey,
        String channel,
        String answer,
        String model,
        long createdAt,
        Long completedAt,
        String errorMessage
) {
}
