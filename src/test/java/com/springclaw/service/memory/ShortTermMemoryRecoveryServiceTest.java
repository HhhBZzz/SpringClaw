package com.springclaw.service.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.store.RedisShortTermMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3A1 Task 6：短期记忆恢复服务。
 *
 * 守住不变量：
 *   - rebuild 获取 per-scope lease，委托 store.mergeRecovery，然后释放 lease；
 *   - personal-session owner 与 claim 不符时，在查询 chat 事件前抛错；
 *   - 持久化行高于 watermark 的不并入。
 */
class ShortTermMemoryRecoveryServiceTest {

    private static RedissonClient redisson;
    private RedisShortTermMemoryStore store;
    private ShortTermMemoryRecoveryService recovery;
    private MessageEventService messageEventService;
    private MemoryScope scope;

    @BeforeAll
    static void setUpRedis() {
        org.redisson.config.Config config = new org.redisson.config.Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        redisson = Redisson.create(config);
    }

    @BeforeEach
    void setUp() {
        String unique = "t6rec-" + java.util.UUID.randomUUID();
        scope = MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api", unique, "alice"));
        store = new RedisShortTermMemoryStore(redisson, 40, 3600);
        messageEventService = mock(MessageEventService.class);
        recovery = new ShortTermMemoryRecoveryService(redisson, store, messageEventService);
    }

    @AfterEach
    void tearDown() {
        if (redisson != null && scope != null) {
            redisson.getKeys().delete(shortTermKey(scope));
        }
        if (recovery != null && scope != null) {
            recovery.releaseLease(scope);
        }
    }

    @Test
    void rebuildAcquiresLeaseMergesAndReleases() {
        store.append(scope, entry(13, "event-13"));

        recovery.rebuild(scope, 12, List.of(
                entry(10, "event-10"),
                entry(11, "event-11"),
                entry(12, "event-12")
        ));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L, 11L, 12L, 13L);
    }

    @Test
    void rebuildIgnoresEntriesAboveWatermark() {
        recovery.rebuild(scope, 12, List.of(
                entry(10, "event-10"),
                entry(20, "event-20")
        ));

        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L);
    }

    @Test
    void personalScopeOwnerMismatchThrowsBeforeRecovery() {
        // scope owner = alice；持久化行的 userId = bob，与 claim owner 不符。
        assertThatThrownBy(() -> recovery.rebuild(scope, 12, List.of(
                entryForUser(10, "event-10", "bob")
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void recoverLoadsOnlyAuthorizedChatUserAndAssistantEvents() {
        when(messageEventService.listSessionEvents(
                scope.sessionKey(), scope.authorizationPrincipal(), null, "CHAT", 100, true
        )).thenReturn(List.of(
                event(10, "chat:req-1:user", "USER", "alice", "hello"),
                event(11, "chat:req-1:system", "SYSTEM", "alice", "audit"),
                event(12, "chat:req-1:assistant:terminal", "ASSISTANT", "alice", "answer"),
                event(13, "chat:req-2:user", "USER", "bob", "wrong owner")
        ));

        long watermark = recovery.recover(scope, 100);

        assertThat(watermark).isEqualTo(12L);
        assertThat(store.readRecent(scope, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(10L, 12L);
        verify(messageEventService).listSessionEvents(
                scope.sessionKey(), scope.authorizationPrincipal(), null, "CHAT", 100, true);
    }

    private static ShortTermMemoryEntry entry(long id, String eventKey) {
        return entryForUser(id, eventKey, "alice");
    }

    private static ShortTermMemoryEntry entryForUser(long id, String eventKey, String userId) {
        return new ShortTermMemoryEntry(
                id, eventKey, "req-1", "USER", userId, "content-" + id,
                Instant.parse("2026-06-23T00:00:0" + (id % 10) + "Z"));
    }

    private static MessageEvent event(long id,
                                      String eventKey,
                                      String role,
                                      String userId,
                                      String content) {
        MessageEvent event = new MessageEvent();
        event.setId(id);
        event.setEventKey(eventKey);
        event.setRequestId("req-1");
        event.setRole(role);
        event.setEventType("CHAT");
        event.setUserId(userId);
        event.setContent(content);
        event.setCreateTime(LocalDateTime.of(2026, 6, 23, 0, 0, (int) (id % 10)));
        return event;
    }

    private static String shortTermKey(MemoryScope scope) {
        return "springclaw:memory:short-term:" + scope.scopeType().name() + ":" + scope.scopeId();
    }
}
