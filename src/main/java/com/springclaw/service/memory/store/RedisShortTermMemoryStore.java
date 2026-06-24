package com.springclaw.service.memory.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;

/**
 * Phase 3A1 Task 6：Redis 短期记忆影子存储。
 *
 * <p>每个 scope 一个 ZSET：score=persisted eventId，member=canonical JSON（含 eventKey）。
 * 一次 Lua 完成 ZADD NX + trim + EXPIRE，保证 append 原子且按 eventId 排序。乱序追加仍按
 * eventId 升序读出。mergeRecovery 只补 eventId<=watermark 的行，不删除既有 key。
 */
public class RedisShortTermMemoryStore implements ShortTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisShortTermMemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String APPEND_SCRIPT = """
            local key = KEYS[1]
            local score = tonumber(ARGV[1])
            local member = ARGV[2]
            local maxEntries = tonumber(ARGV[3])
            local ttlSeconds = tonumber(ARGV[4])
            redis.call('ZADD', key, 'NX', score, member)
            redis.call('ZREMRANGEBYRANK', key, 0, -(maxEntries + 1))
            redis.call('EXPIRE', key, ttlSeconds)
            return 1
            """;

    private final RedissonClient redissonClient;
    private final int maxEntries;
    private final long ttlSeconds;

    public RedisShortTermMemoryStore(RedissonClient redissonClient,
                                     int maxEntries,
                                     long ttlSeconds) {
        this.redissonClient = redissonClient;
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    @Override
    public void append(MemoryScope scope, ShortTermMemoryEntry entry) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(entry, "entry");
        try {
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    APPEND_SCRIPT,
                    RScript.ReturnType.INTEGER,
                    Collections.singletonList(key(scope)),
                    String.valueOf(entry.eventId()),
                    encode(entry),
                    String.valueOf(maxEntries),
                    String.valueOf(ttlSeconds)
            );
        } catch (Exception ex) {
            log.warn("Redis 短期记忆 append 失败，降级 MySQL 恢复源。scope={}, reason={}",
                    scope.scopeId(), ex.getMessage());
        }
    }

    @Override
    public List<ShortTermMemoryEntry> readRecent(MemoryScope scope, int limit) {
        Objects.requireNonNull(scope, "scope");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try {
            List<String> members = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_ONLY,
                    "local key = KEYS[1] local n = tonumber(ARGV[1]) "
                            + "local count = redis.call('ZCARD', key) "
                            + "local from = math.max(0, count - n) "
                            + "return redis.call('ZRANGE', key, from, -1)",
                    RScript.ReturnType.MULTI,
                    Collections.singletonList(key(scope)),
                    String.valueOf(limit)
            );
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<ShortTermMemoryEntry> entries = new ArrayList<>(members.size());
            for (Object member : members) {
                ShortTermMemoryEntry decoded = decode(String.valueOf(member));
                if (decoded != null) {
                    entries.add(decoded);
                }
            }
            return List.copyOf(entries);
        } catch (Exception ex) {
            log.warn("Redis 短期记忆读取失败，降级返回空。scope={}, reason={}",
                    scope.scopeId(), ex.getMessage());
            return List.of();
        }
    }

    @Override
    public void mergeRecovery(
            MemoryScope scope,
            long watermark,
            List<ShortTermMemoryEntry> persistedEntries
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(persistedEntries, "persistedEntries");
        for (ShortTermMemoryEntry entry : persistedEntries) {
            Objects.requireNonNull(entry, "persisted entry");
            if (entry.eventId() > watermark) {
                continue;
            }
            append(scope, entry);
        }
    }

    /** 测试辅助：清理 scope key。 */
    void deleteScope(MemoryScope scope) {
        try {
            redissonClient.getBucket(key(scope)).delete();
            redissonClient.getKeys().delete(key(scope));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static String key(MemoryScope scope) {
        return "springclaw:memory:short-term:" + scope.scopeType().name() + ":" + scope.scopeId();
    }

    private static String encode(ShortTermMemoryEntry entry) {
        try {
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("eventId", entry.eventId());
            map.put("eventKey", entry.eventKey());
            map.put("requestId", entry.requestId());
            map.put("role", entry.role());
            map.put("userId", entry.userId());
            map.put("content", entry.content());
            map.put("occurredAt", entry.occurredAt().toString());
            return MAPPER.writeValueAsString(map);
        } catch (Exception ex) {
            throw new IllegalStateException("encode short-term entry failed", ex);
        }
    }

    private static ShortTermMemoryEntry decode(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, MAP_TYPE);
            return new ShortTermMemoryEntry(
                    ((Number) map.get("eventId")).longValue(),
                    (String) map.get("eventKey"),
                    (String) map.get("requestId"),
                    (String) map.get("role"),
                    (String) map.get("userId"),
                    (String) map.get("content"),
                    Instant.parse((String) map.get("occurredAt"))
            );
        } catch (Exception ex) {
            log.warn("decode short-term entry failed: {}", json);
            return null;
        }
    }
}
