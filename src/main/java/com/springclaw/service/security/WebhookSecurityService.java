package com.springclaw.service.security;

import java.util.Map;

/**
 * Webhook 安全校验服务。
 */
public interface WebhookSecurityService {

    void verify(String channel, Map<String, String> headers, String rawBody);
}
