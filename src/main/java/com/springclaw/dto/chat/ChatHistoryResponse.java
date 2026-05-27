package com.springclaw.dto.chat;

import java.util.List;

/**
 * 会话历史响应。
 */
public record ChatHistoryResponse(
        String sessionKey,
        List<ChatHistoryMessage> messages
) {
}
