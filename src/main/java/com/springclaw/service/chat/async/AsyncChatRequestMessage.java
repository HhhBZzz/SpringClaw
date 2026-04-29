package com.springclaw.service.chat.async;

public record AsyncChatRequestMessage(
        String requestId,
        String sessionKey,
        String userId,
        String message,
        String channel,
        long createdAt
) {
}
