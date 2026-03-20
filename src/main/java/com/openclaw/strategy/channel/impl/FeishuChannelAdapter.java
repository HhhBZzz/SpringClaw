package com.openclaw.strategy.channel.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.common.exception.BusinessException;
import com.openclaw.common.support.ConversationScopeSupport;
import com.openclaw.strategy.channel.ChannelAdapter;
import com.openclaw.strategy.channel.model.UnifiedInboundMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Feishu Webhook 适配器。
 *
 * 支持两类输入：
 * 1) 飞书事件回调标准结构（event.sender/event.message）
 * 2) 本地联调用简化结构（open_id/chat_id/text）
 */
@Component
@SuppressWarnings("unchecked")
public class FeishuChannelAdapter implements ChannelAdapter {

    private final ObjectMapper objectMapper;

    public FeishuChannelAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "feishu";
    }

    @Override
    public UnifiedInboundMessage adapt(Map<String, Object> payload) {
        Map<String, Object> event = map(payload.get("event"));
        if (event != null) {
            return adaptFromEvent(event);
        }
        return adaptFromSimple(payload);
    }

    private UnifiedInboundMessage adaptFromEvent(Map<String, Object> event) {
        Map<String, Object> sender = map(event.get("sender"));
        Map<String, Object> senderId = sender == null ? null : map(sender.get("sender_id"));
        Map<String, Object> message = map(event.get("message"));

        String openId = senderId == null ? "" : firstNonBlank(
                str(senderId.get("open_id")),
                str(senderId.get("user_id")),
                str(senderId.get("union_id"))
        );
        String chatId = message == null ? "" : str(message.get("chat_id"));
        String chatType = message == null ? "" : str(message.get("chat_type"));
        String messageType = message == null ? "" : str(message.get("message_type"));
        String contentRaw = message == null ? "" : str(message.get("content"));
        String text = extractText(messageType, contentRaw);

        if (!StringUtils.hasText(openId) || !StringUtils.hasText(chatId) || !StringUtils.hasText(text)) {
            throw new BusinessException(40031, "Feishu payload 字段不完整");
        }

        return new UnifiedInboundMessage(channel(), ConversationScopeSupport.buildFeishuSessionKey(chatType, chatId), openId, text);
    }

    private UnifiedInboundMessage adaptFromSimple(Map<String, Object> payload) {
        String openId = str(payload.get("open_id"));
        String chatId = str(payload.get("chat_id"));
        String chatType = str(payload.get("chat_type"));
        String text = str(payload.get("text"));

        if (!StringUtils.hasText(openId) || !StringUtils.hasText(chatId) || !StringUtils.hasText(text)) {
            throw new BusinessException(40031, "Feishu payload 字段不完整");
        }

        return new UnifiedInboundMessage(channel(), ConversationScopeSupport.buildFeishuSessionKey(chatType, chatId), openId, text);
    }

    private String extractText(String messageType, String contentRaw) {
        if (!StringUtils.hasText(contentRaw)) {
            return "";
        }
        if (!"text".equalsIgnoreCase(messageType)) {
            return contentRaw.trim();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(contentRaw, new TypeReference<>() {
            });
            Object text = map.get("text");
            return text == null ? "" : String.valueOf(text).trim();
        } catch (Exception ex) {
            throw new BusinessException(40032, "Feishu 文本内容解析失败: " + ex.getMessage());
        }
    }

    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }
}
