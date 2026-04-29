package com.springclaw.strategy.channel.outbound.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.support.ConversationScopeSupport;
import com.springclaw.strategy.channel.model.UnifiedInboundMessage;
import com.springclaw.strategy.channel.outbound.ChannelOutboundAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * 飞书出站适配器（主动回消息）。
 */
@Component
@SuppressWarnings("unchecked")
public class FeishuChannelOutboundAdapter implements ChannelOutboundAdapter {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String baseUrl;
    private final String appId;
    private final String appSecret;

    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    public FeishuChannelOutboundAdapter(ObjectMapper objectMapper,
                                        @Value("${springclaw.channel.feishu.outbound-enabled:false}") boolean enabled,
                                        @Value("${springclaw.channel.feishu.base-url:https://open.feishu.cn}") String baseUrl,
                                        @Value("${springclaw.channel.feishu.app-id:}") String appId,
                                        @Value("${springclaw.channel.feishu.app-secret:}") String appSecret) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "https://open.feishu.cn" : baseUrl.trim();
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.cachedToken = "";
        this.tokenExpireAt = 0L;
    }

    @Override
    public String channel() {
        return "feishu";
    }

    @Override
    public void send(UnifiedInboundMessage inboundMessage, Map<String, Object> rawPayload, String replyText) {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw new BusinessException(50021, "飞书回消息已开启，但未配置 app-id/app-secret");
        }

        String chatId = resolveChatId(inboundMessage, rawPayload);
        if (!StringUtils.hasText(chatId)) {
            throw new BusinessException(40033, "飞书回消息失败：无法解析 chat_id");
        }

        String token = getTenantAccessToken();
        String content = toFeishuTextContent(replyText);

        Map<String, Object> request = Map.of(
                "receive_id", chatId,
                "msg_type", "text",
                "content", content
        );

        Map<String, Object> response = restClient.post()
                .uri(baseUrl + "/open-apis/im/v1/messages?receive_id_type=chat_id")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .body(request)
                .retrieve()
                .body(Map.class);

        int code = asInt(response == null ? null : response.get("code"));
        if (code != 0) {
            String msg = response == null ? "unknown" : String.valueOf(response.getOrDefault("msg", "unknown"));
            throw new BusinessException(50022, "飞书回消息失败，code=" + code + ", msg=" + msg);
        }
    }

    private String getTenantAccessToken() {
        long now = Instant.now().getEpochSecond();
        if (StringUtils.hasText(cachedToken) && now < tokenExpireAt - 60) {
            return cachedToken;
        }
        synchronized (this) {
            now = Instant.now().getEpochSecond();
            if (StringUtils.hasText(cachedToken) && now < tokenExpireAt - 60) {
                return cachedToken;
            }

            Map<String, Object> request = Map.of(
                    "app_id", appId,
                    "app_secret", appSecret
            );

            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            int code = asInt(response == null ? null : response.get("code"));
            if (code != 0) {
                String msg = response == null ? "unknown" : String.valueOf(response.getOrDefault("msg", "unknown"));
                throw new BusinessException(50023, "飞书获取 tenant_access_token 失败，code=" + code + ", msg=" + msg);
            }

            String token = response == null ? "" : String.valueOf(response.getOrDefault("tenant_access_token", ""));
            long expire = asLong(response == null ? null : response.get("expire"));
            if (!StringUtils.hasText(token)) {
                throw new BusinessException(50024, "飞书 tenant_access_token 为空");
            }

            cachedToken = token;
            tokenExpireAt = now + (expire > 0 ? expire : 7200L);
            return cachedToken;
        }
    }

    private String resolveChatId(UnifiedInboundMessage inboundMessage, Map<String, Object> rawPayload) {
        String sessionKey = inboundMessage == null ? "" : inboundMessage.sessionKey();
        String chatIdFromSession = ConversationScopeSupport.resolveFeishuChatId(sessionKey);
        if (StringUtils.hasText(chatIdFromSession)) {
            return chatIdFromSession;
        }

        Object chatId = rawPayload == null ? null : rawPayload.get("chat_id");
        if (chatId != null && StringUtils.hasText(String.valueOf(chatId))) {
            return String.valueOf(chatId).trim();
        }

        Object eventObj = rawPayload == null ? null : rawPayload.get("event");
        if (!(eventObj instanceof Map<?, ?> event)) {
            return "";
        }
        Object messageObj = event.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            return "";
        }
        Object value = message.get("chat_id");
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String toFeishuTextContent(String replyText) {
        String safeText = replyText == null ? "" : replyText;
        if (safeText.length() > 1800) {
            safeText = safeText.substring(0, 1800) + "...";
        }
        try {
            return objectMapper.writeValueAsString(Map.of("text", safeText));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(50025, "飞书消息序列化失败: " + ex.getMessage());
        }
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return -1;
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0L;
        }
    }
}
