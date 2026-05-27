package com.springclaw.dto.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话请求。
 */
public record ChatRequest(
        @NotBlank(message = "sessionKey 不能为空") String sessionKey,
        String userId,
        @NotBlank(message = "message 不能为空") String message,
        String channel,
        String responseMode
) {
    public ChatRequest(String sessionKey, String userId, String message, String channel) {
        this(sessionKey, userId, message, channel, null);
    }
}
