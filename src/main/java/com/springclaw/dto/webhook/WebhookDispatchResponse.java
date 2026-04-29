package com.springclaw.dto.webhook;

/**
 * Webhook 分发响应。
 */
public record WebhookDispatchResponse(
        String channel,
        String sessionKey,
        String reply
) {
}
