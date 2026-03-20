package com.openclaw.strategy.channel.impl;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.strategy.channel.ChannelAdapter;
import com.openclaw.strategy.channel.model.UnifiedInboundMessage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Telegram Webhook 适配器。
 */
@Component
@SuppressWarnings("unchecked")
public class TelegramChannelAdapter implements ChannelAdapter {

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public UnifiedInboundMessage adapt(Map<String, Object> payload) {
        Map<String, Object> message = (Map<String, Object>) payload.get("message");
        if (message == null) {
            throw new BusinessException(40010, "Telegram payload 缺少 message 字段");
        }

        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        String text = String.valueOf(message.getOrDefault("text", "")).trim();

        if (chat == null || from == null || text.isEmpty()) {
            throw new BusinessException(40011, "Telegram payload 字段不完整");
        }

        String chatId = String.valueOf(chat.get("id"));
        String userId = String.valueOf(from.get("id"));
        String sessionKey = "telegram:" + chatId;

        return new UnifiedInboundMessage(channel(), sessionKey, userId, text);
    }
}
