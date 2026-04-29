package com.springclaw.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.dto.webhook.WebhookDispatchResponse;
import com.springclaw.service.security.WebhookSecurityService;
import com.springclaw.service.webhook.WebhookRouterService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 统一 Webhook 入口。
 *
 * 设计说明：
 * 1. 统一入口 + 策略分发是“多渠道路由底座”的关键。
 * 2. 通过 /api/webhook/{channel}，新增渠道只扩展策略实现，不改 Controller。
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private final WebhookRouterService webhookRouterService;
    private final WebhookSecurityService webhookSecurityService;
    private final ObjectMapper objectMapper;

    public WebhookController(WebhookRouterService webhookRouterService,
                             WebhookSecurityService webhookSecurityService,
                             ObjectMapper objectMapper) {
        this.webhookRouterService = webhookRouterService;
        this.webhookSecurityService = webhookSecurityService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{channel}")
    public Object inbound(@PathVariable String channel,
                          @RequestHeader Map<String, String> headers,
                          @RequestBody String rawBody) {
        Map<String, Object> payload = parsePayload(rawBody);
        Object quickResponse = handleFeishuUrlVerification(channel, payload);
        if (quickResponse != null) {
            return quickResponse;
        }

        webhookSecurityService.verify(channel, headers, rawBody);
        return ApiResponse.success("Webhook 处理成功", webhookRouterService.dispatch(channel, payload));
    }

    private Object handleFeishuUrlVerification(String channel, Map<String, Object> payload) {
        if (!"feishu".equalsIgnoreCase(channel)) {
            return null;
        }
        String type = String.valueOf(payload.getOrDefault("type", "")).trim();
        Object challenge = payload.get("challenge");
        if ("url_verification".equals(type) && challenge != null) {
            return Map.of("challenge", String.valueOf(challenge));
        }
        return null;
    }

    private Map<String, Object> parsePayload(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new BusinessException(40040, "Webhook JSON 解析失败: " + ex.getMessage());
        }
    }
}
