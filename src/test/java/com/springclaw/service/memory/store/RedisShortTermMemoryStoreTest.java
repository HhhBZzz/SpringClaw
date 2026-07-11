package com.springclaw.service.memory.store;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3A1 Task 6：Redis 短期记忆影子存储。
 *
 * 守住不变量：
 *   - append 按 eventId 排序（score=persisted eventId），乱序追加仍按 id 升序读出；
 *   - 同 eventKey 幂等（ZADD NX）；
 *   - trim 到最多 maxEntries 条；
 *   - mergeRecovery 只补 eventId<=watermark 的行，不删除既有 key；
 *   - 高于 watermark 的并发 append 在 recovery 后保留。
 *
 * 依赖本机 Redis（127.0.0.1:6379）；每个测试用唯一 scope 并在结束后清理 key。
 */
class RedisShortTermMemoryStoreTest {

    private static RedissonClient redisson;
    private RedisShortTermMemoryStore store;
    private MemoryScope scope;

    @BeforeAll
    static void setUpRedis() {
        org.redisson.config.Config config = new org.redisson.config.Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        redisson = Redisson.create(config);
    }

    @BeforeEach
    void setUp() {
        String unique = "t6-" + java.util.UUID.randomUUID();
        scope = MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api", unique, "alice"));
        store = new RedisShortTermMemoryStore(redisson, 40, 3600);
    }

    @AfterEach
    void tearDown() {
        if (store != null && scope != null) {
            store.deleteScope(scope);
        }
    }

    @Test
    void delayedAppendKeepsDatabaseIdOrder() {
        store.append(scope, entry(12, "event-12"));
        store.append(scope, entry(10, "event-10"));
        store.append(scope, entry(11, "event-11"));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L, 11L, 12L);
    }

    @Test
    void appendIsIdempotentByEventKey() {
        store.append(scope, entry(10, "event-10"));
        store.append(scope, entry(10, "event-10"));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L);
    }

    @Test
    void appendRemainsIdempotentWhenReplayTimestampChanges() {
        store.append(scope, entry(10, "event-10"));
        store.append(scope, new ShortTermMemoryEntry(
                10L,
                "event-10",
                "req-1",
                "USER",
                "alice",
                "content-replayed",
                Instant.parse("2026-06-24T00:00:00Z")
        ));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventKey)
                .containsExactly("event-10");
    }

    @Test
    void readRecentReturnsAtMostLimitNewest() {
        store.append(scope, entry(1, "event-1"));
        store.append(scope, entry(2, "event-2"));
        store.append(scope, entry(3, "event-3"));

        assertThat(store.readRecent(scope, 2))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(2L, 3L);
    }

    @Test
    void appendTrimsToMaxEntries() {
        for (long id = 1; id <= 45; id++) {
            store.append(scope, entry(id, "event-" + id));
        }

        List<ShortTermMemoryEntry> recent = store.readRecent(scope, 100);
        assertThat(recent).hasSize(40);
        assertThat(recent.get(0).eventId()).isEqualTo(6L);
        assertThat(recent.get(39).eventId()).isEqualTo(45L);
    }

    @Test
    void recoveryMergePreservesConcurrentAppendAboveWatermark() {
        store.append(scope, entry(13, "event-13"));
        store.mergeRecovery(scope, 12, List.of(
                entry(10, "event-10"),
                entry(11, "event-11"),
                entry(12, "event-12")
        ));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L, 11L, 12L, 13L);
    }

    @Test
    void recoveryMergeIgnoresEntriesAboveWatermark() {
        store.mergeRecovery(scope, 12, List.of(
                entry(10, "event-10"),
                entry(15, "event-15")
        ));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L);
    }

    @Test
    void recoveryMergeDoesNotDuplicateExistingEventKey() {
        store.append(scope, entry(10, "event-10"));
        store.mergeRecovery(scope, 12, List.of(
                entry(10, "event-10"),
                entry(11, "event-11")
        ));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L, 11L);
    }

    @Test
    void appendFailureDoesNotPropagateToChatPath() {
        RedissonClient failingRedisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(failingRedisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.INTEGER),
                any(List.class),
                any()
        )).thenThrow(new IllegalStateException("redis unavailable"));

        RedisShortTermMemoryStore failingStore =
                new RedisShortTermMemoryStore(failingRedisson, 40, 3600);

        assertThatCode(() -> failingStore.append(scope, entry(10, "event-10")))
                .doesNotThrowAnyException();
    }

    private static ShortTermMemoryEntry entry(long id, String eventKey) {
        return new ShortTermMemoryEntry(
                id, eventKey, "req-1", "USER", "alice", "content-" + id,
                Instant.parse("2026-06-23T00:00:0" + (id % 10) + "Z"));
    }
}
