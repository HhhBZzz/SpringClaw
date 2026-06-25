package com.springclaw.service.memory.store;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.springclaw.domain.entity.MemoryIndexOutboxEntity;
import com.springclaw.mapper.MemoryIndexOutboxMapper;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlMemoryIndexOutboxStoreTest {

    private static final Instant NOW = Instant.parse("2026-06-23T00:00:00Z");
    private static final Instant LEASE_UNTIL = NOW.plusSeconds(30);

    @Test
    void claimNextReturnsEmptyWhenClaimWasReclaimedBeforeTokenFencedRead() {
        MemoryIndexOutboxMapper mapper = mock(MemoryIndexOutboxMapper.class);
        MySqlMemoryIndexOutboxStore store = new MySqlMemoryIndexOutboxStore(mapper);
        MemoryIndexOutboxEntity candidate = candidate();
        when(mapper.selectFencedCandidate(toLocalDateTime(NOW))).thenReturn(candidate);
        when(mapper.claimById(
                eq(candidate.getId()),
                eq(candidate.getLogicalMemoryId()),
                eq(candidate.getIndexRevision()),
                eq("worker-a"),
                anyString(),
                eq(toLocalDateTime(NOW)),
                eq(toLocalDateTime(LEASE_UNTIL))
        )).thenReturn(1);
        when(mapper.selectClaimedByIdAndToken(eq(candidate.getId()), anyString()))
                .thenReturn(null);
        when(mapper.selectOne(any(Wrapper.class)))
                .thenReturn(claimed("other-worker-token"));

        Optional<MemoryIndexOutboxEntry> result =
                store.claimNext("worker-a", NOW, LEASE_UNTIL);

        assertThat(result).isEmpty();
    }

    @Test
    void claimNextReturnsClaimWhenTokenFencedReadFindsSameToken() {
        MemoryIndexOutboxMapper mapper = mock(MemoryIndexOutboxMapper.class);
        MySqlMemoryIndexOutboxStore store = new MySqlMemoryIndexOutboxStore(mapper);
        MemoryIndexOutboxEntity candidate = candidate();
        when(mapper.selectFencedCandidate(toLocalDateTime(NOW))).thenReturn(candidate);
        when(mapper.claimById(
                eq(candidate.getId()),
                eq(candidate.getLogicalMemoryId()),
                eq(candidate.getIndexRevision()),
                eq("worker-a"),
                anyString(),
                eq(toLocalDateTime(NOW)),
                eq(toLocalDateTime(LEASE_UNTIL))
        )).thenReturn(1);
        when(mapper.selectClaimedByIdAndToken(eq(candidate.getId()), anyString()))
                .thenAnswer(invocation -> claimed(invocation.getArgument(1)));

        Optional<MemoryIndexOutboxEntry> result =
                store.claimNext("worker-a", NOW, LEASE_UNTIL);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mapper).claimById(
                eq(candidate.getId()),
                eq(candidate.getLogicalMemoryId()),
                eq(candidate.getIndexRevision()),
                eq("worker-a"),
                token.capture(),
                eq(toLocalDateTime(NOW)),
                eq(toLocalDateTime(LEASE_UNTIL))
        );
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().claimToken()).isEqualTo(token.getValue());
    }

    private static MemoryIndexOutboxEntity candidate() {
        MemoryIndexOutboxEntity entity = new MemoryIndexOutboxEntity();
        entity.setId(1L);
        entity.setLogicalMemoryId("logical-1");
        entity.setIndexRevision(1L);
        return entity;
    }

    private static MemoryIndexOutboxEntity claimed(String token) {
        return MemoryIndexOutboxEntity.fromDomain(new MemoryIndexOutboxEntry(
                "event-1",
                "logical-1",
                "version-1",
                1,
                1L,
                MemoryIndexOperation.UPSERT,
                MemoryIndexOutboxEntry.Status.CLAIMED,
                1,
                NOW,
                NOW,
                "worker-a",
                token,
                LEASE_UNTIL,
                null,
                NOW,
                NOW
        ));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
