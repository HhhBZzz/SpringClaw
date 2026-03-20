package com.openclaw.service.ai;

import com.openclaw.config.ai.OpenClawAiProperties;
import com.openclaw.domain.entity.MessageEvent;
import com.openclaw.service.event.MessageEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型提供方当前状态持久化。
 *
 * 设计说明：
 * 1. Redis 保存“当前活动 provider”和“provider 当前 model”，用于重启后恢复当前选择。
 * 2. message_event 记录切换审计，便于排查与在 Redis 不可用时回退恢复。
 */
@Service
public class AiProviderStateService {

    private static final Pattern PROVIDER_PATTERN = Pattern.compile("provider=([a-z0-9\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_PATTERN = Pattern.compile("model=([^,\\s]+)", Pattern.CASE_INSENSITIVE);

    private final StringRedisTemplate redisTemplate;
    private final MessageEventService messageEventService;
    private final boolean dbEnabled;
    private final boolean redisEnabled;
    private final String redisKey;
    private final String redisModelPrefix;
    private final String auditSessionKey;

    public AiProviderStateService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                  MessageEventService messageEventService,
                                  OpenClawAiProperties aiProperties,
                                  @Value("${openclaw.persistence.db-enabled:false}") boolean dbEnabled) {
        this.redisTemplate = redisTemplate;
        this.messageEventService = messageEventService;
        this.dbEnabled = dbEnabled;
        this.redisEnabled = aiProperties.getState().isRedisEnabled();
        this.redisKey = aiProperties.getState().getRedisKey();
        this.redisModelPrefix = aiProperties.getState().getRedisModelPrefix();
        this.auditSessionKey = aiProperties.getState().getAuditSessionKey();
    }

    public String resolvePreferredProvider(String configuredDefault) {
        String redisValue = readFromRedis();
        if (StringUtils.hasText(redisValue)) {
            return redisValue;
        }
        String latestAudit = readProviderFromAudit();
        if (StringUtils.hasText(latestAudit)) {
            return latestAudit;
        }
        return configuredDefault;
    }

    public String resolvePreferredModel(String providerId, String configuredDefault) {
        String redisValue = readModelFromRedis(providerId);
        if (StringUtils.hasText(redisValue)) {
            return redisValue;
        }
        String latestAudit = readModelFromAudit(providerId);
        if (StringUtils.hasText(latestAudit)) {
            return latestAudit;
        }
        return configuredDefault;
    }

    public void persistActiveProvider(String providerId, String source) {
        persistActiveState(providerId, "", source);
    }

    public void persistActiveState(String providerId, String modelId, String source) {
        if (!StringUtils.hasText(providerId)) {
            return;
        }
        String normalizedProvider = providerId.trim().toLowerCase(Locale.ROOT);
        if (redisEnabled && redisTemplate != null) {
            redisTemplate.opsForValue().set(redisKey, normalizedProvider);
            if (StringUtils.hasText(modelId)) {
                redisTemplate.opsForValue().set(redisModelPrefix + normalizedProvider, modelId.trim());
            }
        }
        if (dbEnabled) {
            String content = "provider=%s, model=%s, source=%s".formatted(
                    normalizedProvider,
                    StringUtils.hasText(modelId) ? modelId.trim() : "",
                    StringUtils.hasText(source) ? source.trim() : "runtime"
            );
            messageEventService.recordSingle(
                    auditSessionKey,
                    "system",
                    "system",
                    "SYSTEM",
                    "MODEL_PROVIDER_SWITCH",
                    content,
                    ""
            );
        }
    }

    public String switchMode() {
        boolean redisAvailable = redisEnabled && redisTemplate != null;
        if (redisAvailable && dbEnabled) {
            return "redis+db";
        }
        if (redisAvailable) {
            return "redis";
        }
        if (dbEnabled) {
            return "db";
        }
        return "runtime-memory";
    }

    private String readFromRedis() {
        if (!redisEnabled || redisTemplate == null) {
            return "";
        }
        String value = redisTemplate.opsForValue().get(redisKey);
        return normalizeProvider(value);
    }

    private String readModelFromRedis(String providerId) {
        if (!redisEnabled || redisTemplate == null || !StringUtils.hasText(providerId)) {
            return "";
        }
        String value = redisTemplate.opsForValue().get(redisModelPrefix + normalizeProvider(providerId));
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String readProviderFromAudit() {
        if (!dbEnabled) {
            return "";
        }
        List<MessageEvent> events = messageEventService.listRecent(auditSessionKey, 30);
        for (int i = events.size() - 1; i >= 0; i--) {
            String provider = extractProvider(events.get(i).getContent());
            if (StringUtils.hasText(provider)) {
                return provider;
            }
        }
        return "";
    }

    private String readModelFromAudit(String providerId) {
        if (!dbEnabled || !StringUtils.hasText(providerId)) {
            return "";
        }
        String normalizedProvider = normalizeProvider(providerId);
        List<MessageEvent> events = messageEventService.listRecent(auditSessionKey, 50);
        for (int i = events.size() - 1; i >= 0; i--) {
            String content = events.get(i).getContent();
            if (!normalizedProvider.equals(extractProvider(content))) {
                continue;
            }
            String model = extractModel(content);
            if (StringUtils.hasText(model)) {
                return model;
            }
        }
        return "";
    }

    private String extractProvider(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        Matcher matcher = PROVIDER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return "";
        }
        return normalizeProvider(matcher.group(1));
    }

    private String extractModel(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        Matcher matcher = MODEL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private String normalizeProvider(String providerId) {
        if (!StringUtils.hasText(providerId)) {
            return "";
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
