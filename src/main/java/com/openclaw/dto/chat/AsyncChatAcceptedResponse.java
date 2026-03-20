package com.openclaw.dto.chat;

public record AsyncChatAcceptedResponse(
        String requestId,
        String status,
        String channel,
        long timestamp
) {
}
