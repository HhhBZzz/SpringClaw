package com.springclaw.service.webhook;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.dto.webhook.WebhookDispatchResponse;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.strategy.channel.ChannelAdapter;
import com.springclaw.strategy.channel.factory.ChannelAdapterFactory;
import com.springclaw.strategy.channel.model.UnifiedInboundMessage;
import com.springclaw.strategy.channel.outbound.ChannelOutboundDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

/**
 * Webhook 路由服务。
 *
 * 设计说明：
 * 1. Controller 只接请求，不做渠道解析；解析责任在策略层，编排责任在 Service。
 * 2. 这种职责边界清晰，后续做链路追踪、灰度发布、鉴权增强都更容易扩展。
 */
@Service
public class WebhookRouterService {

    private static final Logger log = LoggerFactory.getLogger(WebhookRouterService.class);

    private final ChannelAdapterFactory channelAdapterFactory;
    private final ChatService chatService;
    private final MessageEventService messageEventService;
    private final ChannelOutboundDispatcher channelOutboundDispatcher;

    public WebhookRouterService(ChannelAdapterFactory channelAdapterFactory,
                                ChatService chatService,
                                MessageEventService messageEventService,
                                ChannelOutboundDispatcher channelOutboundDispatcher) {
        this.channelAdapterFactory = channelAdapterFactory;
        this.chatService = chatService;
        this.messageEventService = messageEventService;
        this.channelOutboundDispatcher = channelOutboundDispatcher;
    }

    public WebhookDispatchResponse dispatch(String channel, Map<String, Object> payload) {
        if (isFeishuSelfMessage(channel, payload)) {
            return new WebhookDispatchResponse(channel, "feishu-self-message", "ignored");
        }

        String requestId = UUID.randomUUID().toString().replace("-", "");
        UnifiedInboundMessage inboundMessage = null;
        ChatResponse response = null;
        try {
            ChannelAdapter adapter = channelAdapterFactory.getRequired(channel);
            inboundMessage = adapter.adapt(payload);
            response = chatService.chat(new ChatRequest(
                    inboundMessage.sessionKey(),
                    inboundMessage.userId(),
                    inboundMessage.text(),
                    inboundMessage.channel()
            ));
        } catch (Exception ex) {
            log.warn("Webhook 处理失败，channel={}, requestId={}", channel, requestId, ex);
            throw new BusinessException(50041, "Webhook 处理失败");
        }
        if (inboundMessage == null || response == null) {
            throw new BusinessException(50041, "Webhook 处理失败");
        }

        messageEventService.recordSingle(
                inboundMessage.sessionKey(),
                inboundMessage.channel(),
                inboundMessage.userId(),
                "SYSTEM",
                "WEBHOOK",
                "Webhook 已处理并转发到 ChatService，requestId=" + requestId,
                requestId
        );

        try {
            boolean pushed = channelOutboundDispatcher.dispatch(channel, inboundMessage, payload, response.answer());
            if (pushed) {
                messageEventService.recordSingle(
                        inboundMessage.sessionKey(),
                        inboundMessage.channel(),
                        inboundMessage.userId(),
                        "SYSTEM",
                        "WEBHOOK_OUTBOUND",
                        "渠道回消息发送成功，requestId=" + requestId,
                        requestId
                );
            } else {
                messageEventService.recordSingle(
                        inboundMessage.sessionKey(),
                        inboundMessage.channel(),
                        inboundMessage.userId(),
                        "SYSTEM",
                        "WEBHOOK_OUTBOUND",
                        "渠道回消息未发送，requestId=" + requestId,
                        requestId
                );
            }
        } catch (Exception ex) {
            log.warn("渠道回消息发送失败，channel={}, sessionKey={}, requestId={}, reason={}",
                    channel, inboundMessage.sessionKey(), requestId, ex.getMessage());
            messageEventService.recordSingle(
                    inboundMessage.sessionKey(),
                    inboundMessage.channel(),
                    inboundMessage.userId(),
                    "SYSTEM",
                    "WEBHOOK_OUTBOUND",
                    "渠道回消息发送失败，requestId=" + requestId + ", reason=" + ex.getMessage(),
                    requestId
            );
        }

        return new WebhookDispatchResponse(
                inboundMessage.channel(),
                inboundMessage.sessionKey(),
                response.answer()
        );
    }

    @SuppressWarnings("unchecked")
    private boolean isFeishuSelfMessage(String channel, Map<String, Object> payload) {
        if (!"feishu".equalsIgnoreCase(channel) || payload == null) {
            return false;
        }
        Object eventObj = payload.get("event");
        if (!(eventObj instanceof Map<?, ?> event)) {
            return false;
        }
        Object senderObj = event.get("sender");
        if (!(senderObj instanceof Map<?, ?> sender)) {
            return false;
        }
        Object senderTypeValue = sender.get("sender_type");
        String senderType = senderTypeValue == null ? "" : String.valueOf(senderTypeValue);
        return StringUtils.hasText(senderType) && "app".equalsIgnoreCase(senderType.trim());
    }
}
