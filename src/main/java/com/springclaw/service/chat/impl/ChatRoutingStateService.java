package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天链路策略当前状态持久化。
 */
@Service
public class ChatRoutingStateService {

    private static final Pattern MODE_PATTERN = Pattern.compile("defaultMode=([a-z0-9\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_PATTERN = Pattern.compile("autoUpgrade=(true|false)", Pattern.CASE_INSENSITIVE);

    private final StringRedisTemplate redisTemplate;
    private final MessageEventService messageEventService;
    private final boolean dbEnabled;
    private final boolean redisEnabled;
    private final String redisModeKey;
    private final String redisAutoUpgradeKey;
    private final String auditSessionKey;

    public ChatRoutingStateService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                   MessageEventService messageEventService,
                                   @Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                                   @Value("${springclaw.chat.routing.redis-enabled:true}") boolean redisEnabled,
                                   @Value("${springclaw.chat.routing.redis-mode-key:springclaw:chat:routing:mode}") String redisModeKey,
                                   @Value("${springclaw.chat.routing.redis-auto-upgrade-key:springclaw:chat:routing:auto-upgrade}") String redisAutoUpgradeKey,
                                   @Value("${springclaw.chat.routing.audit-session-key:system:chat:routing}") String auditSessionKey) {
        this.redisTemplate = redisTemplate;
        this.messageEventService = messageEventService;
        this.dbEnabled = dbEnabled;
        this.redisEnabled = redisEnabled;
        this.redisModeKey = redisModeKey;
        this.redisAutoUpgradeKey = redisAutoUpgradeKey;
        this.auditSessionKey = auditSessionKey;
    }

    public String resolveDefaultMode(String configuredDefault) {
        String redisValue = readModeFromRedis();
        if (StringUtils.hasText(redisValue)) {
            return normalizeMode(redisValue);
        }
        String auditValue = readModeFromAudit();
        if (StringUtils.hasText(auditValue)) {
            return normalizeMode(auditValue);
        }
        return normalizeMode(configuredDefault);
    }

    public boolean resolveAutoUpgrade(boolean configuredDefault) {
        Boolean redisValue = readAutoUpgradeFromRedis();
        if (redisValue != null) {
            return redisValue;
        }
        Boolean auditValue = readAutoUpgradeFromAudit();
        if (auditValue != null) {
            return auditValue;
        }
        return configuredDefault;
    }

    public void persistState(String defaultMode, boolean autoUpgrade, String source) {
        String normalizedMode = normalizeMode(defaultMode);
        if (redisEnabled && redisTemplate != null) {
            redisTemplate.opsForValue().set(redisModeKey, normalizedMode);
            redisTemplate.opsForValue().set(redisAutoUpgradeKey, Boolean.toString(autoUpgrade));
        }
        if (dbEnabled) {
            String content = "defaultMode=%s, autoUpgrade=%s, source=%s".formatted(
                    normalizedMode,
                    autoUpgrade,
                    StringUtils.hasText(source) ? source.trim() : "runtime"
            );
            messageEventService.recordSingle(
                    auditSessionKey,
                    "system",
                    "system",
                    "SYSTEM",
                    "CHAT_ROUTING_SWITCH",
                    content,
                    ""
            );
        }
    }

    public Map<String, Object> summary(String configuredDefaultMode, boolean configuredAutoUpgrade) {
        String effectiveMode = resolveDefaultMode(configuredDefaultMode);
        boolean effectiveAutoUpgrade = resolveAutoUpgrade(configuredAutoUpgrade);
        boolean redisAvailable = redisEnabled && redisTemplate != null;
        String switchMode;
        if (redisAvailable && dbEnabled) {
            switchMode = "redis+db";
        } else if (redisAvailable) {
            switchMode = "redis";
        } else if (dbEnabled) {
            switchMode = "db";
        } else {
            switchMode = "runtime-memory";
        }
        return Map.of(
                "configuredDefaultMode", normalizeMode(configuredDefaultMode),
                "effectiveDefaultMode", effectiveMode,
                "configuredAutoUpgrade", configuredAutoUpgrade,
                "effectiveAutoUpgrade", effectiveAutoUpgrade,
                "manualOverrideRoles", List.of("ADMIN", "DEVELOPER"),
                "switchMode", switchMode
        );
    }

    private String readModeFromRedis() {
        if (!redisEnabled || redisTemplate == null) {
            return "";
        }
        return redisTemplate.opsForValue().get(redisModeKey);
    }

    private Boolean readAutoUpgradeFromRedis() {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(redisAutoUpgradeKey);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String readModeFromAudit() {
        if (!dbEnabled) {
            return "";
        }
        List<MessageEvent> events = messageEventService.listRecent(auditSessionKey, 20);
        for (int i = events.size() - 1; i >= 0; i--) {
            String mode = extractMode(events.get(i).getContent());
            if (StringUtils.hasText(mode)) {
                return mode;
            }
        }
        return "";
    }

    private Boolean readAutoUpgradeFromAudit() {
        if (!dbEnabled) {
            return null;
        }
        List<MessageEvent> events = messageEventService.listRecent(auditSessionKey, 20);
        for (int i = events.size() - 1; i >= 0; i--) {
            Boolean value = extractAutoUpgrade(events.get(i).getContent());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String extractMode(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        Matcher matcher = MODE_PATTERN.matcher(content);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private Boolean extractAutoUpgrade(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        Matcher matcher = AUTO_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        return Boolean.parseBoolean(matcher.group(1).trim());
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "simplified";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return "opar".equals(normalized) ? "opar" : "simplified";
    }
}
