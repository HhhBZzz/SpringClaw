package com.springclaw.service.chat.async;

public record AsyncChatRequestMessage(
        String requestId,
        String sessionKey,
        String userId,
        String message,
        String channel,
        long createdAt,
        String responseMode
) {
    public AsyncChatRequestMessage(String requestId,
                                   String sessionKey,
                                   String userId,
                                   String message,
                                   String channel,
                                   long createdAt) {
        this(requestId, sessionKey, userId, message, channel, createdAt, null);
    }
}
