package com.springclaw.service.memory;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.event.MessageEventReceipt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermMemoryWriterTest {

    @Test
    void terminalWritesUseTheCanonicalSnapshotScope() {
        ShortTermMemoryStore store = mock(ShortTermMemoryStore.class);
        ObjectProvider<ShortTermMemoryStore> provider = providerFor(store);
        MemoryScope sharedScope = MemoryScope.from(SessionAccessClaim.sharedVerified(
                "feishu", "shared-session", "alice"));
        ShortTermMemoryWriter writer = new ShortTermMemoryWriter(provider);

        writer.appendTerminal(
                context(sharedScope),
                receipt(10, "chat:req-1:user"), "hello",
                receipt(11, "chat:req-1:assistant:terminal"), "answer"
        );

        ArgumentCaptor<MemoryScope> scopeCaptor = ArgumentCaptor.forClass(MemoryScope.class);
        verify(store, org.mockito.Mockito.times(2)).append(scopeCaptor.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(scopeCaptor.getAllValues()).containsOnly(sharedScope);
    }

    @Test
    void doesNotProjectNonDurableReceiptsIntoShortTermMemory() {
        ShortTermMemoryStore store = mock(ShortTermMemoryStore.class);
        ShortTermMemoryWriter writer = new ShortTermMemoryWriter(providerFor(store));

        writer.appendTerminal(
                context(MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api", "s1", "u1"))),
                receipt(-1, "chat:req-1:user"), "hello",
                receipt(11, "chat:req-1:assistant:terminal"), "answer"
        );

        verify(store, never()).append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ShortTermMemoryStore> providerFor(ShortTermMemoryStore store) {
        ObjectProvider<ShortTermMemoryStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    private static MessageEventReceipt receipt(long id, String eventKey) {
        return new MessageEventReceipt(id, eventKey, Instant.parse("2026-07-11T00:00:00Z"));
    }

    private static ChatContext context(MemoryScope scope) {
        AgentSession session = new AgentSession();
        session.setSessionKey("s1");
        session.setChannel("api");
        session.setUserId("u1");
        MemoryFrame frame = new MemoryFrame(
                "req-1", scope, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), Instant.parse("2026-07-11T00:00:00Z"), "frame-hash"
        );
        ContextSnapshot snapshot = new ContextSnapshot(
                "req-1", "s1", "u1", "api", "u1", "USER", "hello", "hello",
                "system", "", List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                frame, Instant.parse("2026-07-11T00:00:00Z"), "snapshot-hash"
        );
        return new ChatContext(
                session, "api", "u1", "USER", "hello", "hello", "req-1", "system",
                null, null, "simplified", "default", "agent", "general", null, null, snapshot,
                null
        );
    }
}
