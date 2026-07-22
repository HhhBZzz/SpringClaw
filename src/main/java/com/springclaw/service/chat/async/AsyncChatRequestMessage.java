package com.springclaw.service.chat.async;

import com.springclaw.runtime.contract.AgentParadigm;

public record AsyncChatRequestMessage(
        String requestId,
        String sessionKey,
        String userId,
        String message,
        String channel,
        long createdAt,
        String responseMode,
        AgentParadigm paradigm
) {
    public AsyncChatRequestMessage(String requestId,
                                   String sessionKey,
                                   String userId,
                                   String message,
                                   String channel,
                                   long createdAt) {
        this(requestId, sessionKey, userId, message, channel, createdAt, null, null);
    }
}
