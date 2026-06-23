package com.springclaw.service.memory;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.service.event.MessageEventService;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 3A1 Task 6：短期记忆恢复服务。
 *
 * <p>从 MySQL 恢复短期记忆到 Redis 影子存储。流程：
 * <ol>
 *   <li>获取 per-scope lease（token-checked Redis 锁）；</li>
 *   <li>校验持久化行的 personal-session owner 与 claim 一致，否则在查询前抛错；</li>
 *   <li>委托 {@link ShortTermMemoryStore#mergeRecovery} 并入 watermark 以下的行；</li>
 *   <li>释放 lease（token-checked Lua）。</li>
 * </ol>
 */
@Component
@ConditionalOnBean(RedissonClient.class)
public class ShortTermMemoryRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ShortTermMemoryRecoveryService.class);

    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """;

    private final RedissonClient redissonClient;
    private final ShortTermMemoryStore store;
    private final MessageEventService messageEventService;

    public ShortTermMemoryRecoveryService(RedissonClient redissonClient,
                                          ShortTermMemoryStore store,
                                          MessageEventService messageEventService) {
        this.redissonClient = redissonClient;
        this.store = store;
        this.messageEventService = messageEventService;
    }

    /**
     * 从持久化 message_event 恢复当前 scope 的 CHAT user/assistant 短期记忆。
     *
     * <p>watermark 是本次读取到的最大 eligible event id；恢复只合并
     * eventId<=watermark 的行，避免覆盖恢复期间并发 shadow append 的更高 id。
     */
    public long recover(MemoryScope scope, int limit) {
        Objects.requireNonNull(scope, "scope");
        if (messageEventService == null) {
            throw new IllegalStateException("messageEventService is required for MySQL recovery");
        }
        int safeLimit = Math.max(1, Math.min(limit, 5_000));
        String token = acquireLease(scope);
        if (token == null) {
            log.warn("短期记忆恢复未获取 lease，跳过。scope={}", scope.scopeId());
            return 0L;
        }
        try {
            List<MessageEvent> eligibleEvents = loadEligibleEvents(scope, safeLimit);
            long watermark = eligibleEvents.stream()
                    .map(MessageEvent::getId)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(0L);
            if (watermark <= 0) {
                return 0L;
            }
            List<ShortTermMemoryEntry> entries = new ArrayList<>(eligibleEvents.size());
            for (MessageEvent event : eligibleEvents) {
                if (event.getId() != null && event.getId() <= watermark) {
                    entries.add(toEntry(event));
                }
            }
            store.mergeRecovery(scope, watermark, entries);
            return watermark;
        } finally {
            releaseLease(scope, token);
        }
    }

    /**
     * 在 per-scope lease 保护下，把已持久化的短期记忆行并入 Redis。
     * 仅并入 eventId<=watermark 的行；高于 watermark 的并发 append 保留。
     */
    public void rebuild(MemoryScope scope,
                        long watermark,
                        List<ShortTermMemoryEntry> persistedEntries) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(persistedEntries, "persistedEntries");
        verifyPersonalOwner(scope, persistedEntries);

        String token = acquireLease(scope);
        if (token == null) {
            log.warn("短期记忆恢复未获取 lease，跳过。scope={}", scope.scopeId());
            return;
        }
        try {
            store.mergeRecovery(scope, watermark, persistedEntries);
        } finally {
            releaseLease(scope, token);
        }
    }

    private List<MessageEvent> loadEligibleEvents(MemoryScope scope, int limit) {
        String userFilter = scope.scopeType() == MemoryScopeType.PERSONAL_SESSION
                ? scope.authorizationPrincipal()
                : null;
        List<MessageEvent> events = messageEventService.listSessionEvents(
                scope.sessionKey(), userFilter, null, "CHAT", limit, true);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(Objects::nonNull)
                .filter(event -> event.getId() != null && event.getId() > 0)
                .filter(event -> "CHAT".equalsIgnoreCase(event.getEventType()))
                .filter(event -> "USER".equalsIgnoreCase(event.getRole())
                        || "ASSISTANT".equalsIgnoreCase(event.getRole()))
                .filter(event -> scope.scopeType() != MemoryScopeType.PERSONAL_SESSION
                        || scope.authorizationPrincipal().equals(event.getUserId()))
                .filter(event -> event.getEventKey() != null && !event.getEventKey().isBlank())
                .filter(event -> event.getRequestId() != null && !event.getRequestId().isBlank())
                .filter(event -> event.getContent() != null && !event.getContent().isBlank())
                .toList();
    }

    private static ShortTermMemoryEntry toEntry(MessageEvent event) {
        Instant occurredAt = event.getCreateTime() == null
                ? Instant.now()
                : event.getCreateTime().atOffset(ZoneOffset.UTC).toInstant();
        return new ShortTermMemoryEntry(
                event.getId(),
                event.getEventKey(),
                event.getRequestId(),
                event.getRole(),
                event.getUserId(),
                event.getContent(),
                occurredAt
        );
    }

    /** 校验 personal-session scope 的持久化行 owner 与 claim 一致。 */
    private static void verifyPersonalOwner(
            MemoryScope scope,
            List<ShortTermMemoryEntry> persistedEntries
    ) {
        if (scope.scopeType() != MemoryScopeType.PERSONAL_SESSION) {
            return;
        }
        String principal = scope.authorizationPrincipal();
        for (ShortTermMemoryEntry entry : persistedEntries) {
            if (entry == null) {
                continue;
            }
            if (!principal.equals(entry.userId())) {
                throw new IllegalStateException(
                        "persisted personal-session owner does not match claim: "
                                + entry.userId() + " vs " + principal
                );
            }
        }
    }

    private String acquireLease(MemoryScope scope) {
        String token = UUID.randomUUID().toString();
        String key = leaseKey(scope);
        try {
            Boolean ok = redissonClient.getBucket(key).setIfAbsent(token, java.time.Duration.ofSeconds(60));
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception ex) {
            log.warn("短期记忆恢复 lease 获取失败。scope={}, reason={}",
                    scope.scopeId(), ex.getMessage());
            return null;
        }
    }

    void releaseLease(MemoryScope scope) {
        // best-effort release without token（测试清理用）
        try {
            redissonClient.getBucket(leaseKey(scope)).delete();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void releaseLease(MemoryScope scope, String token) {
        try {
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    RELEASE_SCRIPT,
                    RScript.ReturnType.INTEGER,
                    Collections.singletonList(leaseKey(scope)),
                    token
            );
        } catch (Exception ex) {
            log.warn("短期记忆恢复 lease 释放失败。scope={}, reason={}",
                    scope.scopeId(), ex.getMessage());
        }
    }

    private static String leaseKey(MemoryScope scope) {
        return "springclaw:memory:short-term:lease:" + scope.scopeId();
    }
}
