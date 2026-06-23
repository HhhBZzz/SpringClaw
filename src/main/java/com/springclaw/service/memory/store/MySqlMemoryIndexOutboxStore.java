package com.springclaw.service.memory.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.springclaw.domain.entity.MemoryIndexOutboxEntity;
import com.springclaw.mapper.MemoryIndexOutboxMapper;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL memory_index_outbox store。fenced claim 由 mapper 的子查询 + CAS 抢占承担：
 * 只领取同一 logical memory 的最小未完成 revision，且用过期租约可被新 worker 抢占。
 * complete/fail 必须带匹配 claimToken 且租约未过期。
 */
@Component
public class MySqlMemoryIndexOutboxStore implements MemoryIndexOutboxStore {

    private final MemoryIndexOutboxMapper mapper;

    public MySqlMemoryIndexOutboxStore(MemoryIndexOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(MemoryIndexOutboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        mapper.insert(MemoryIndexOutboxEntity.fromDomain(entry));
    }

    @Override
    public Optional<MemoryIndexOutboxEntry> claimNext(
            String owner,
            Instant now,
            Instant leaseUntil
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        LocalDateTime nowLdt = toLocalDateTime(now);
        MemoryIndexOutboxEntity candidate = mapper.selectFencedCandidate(nowLdt);
        if (candidate == null) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        int changed = mapper.claimById(
                candidate.getId(),
                candidate.getLogicalMemoryId(),
                candidate.getIndexRevision(),
                owner,
                token,
                nowLdt,
                toLocalDateTime(leaseUntil)
        );
        if (changed == 0) {
            // 丢失竞态——候选已被其他 worker 抢走。
            return Optional.empty();
        }
        // 重读以拿到 attempts 自增后的最新状态。
        return Optional.ofNullable(
                        mapper.selectClaimedByIdAndToken(candidate.getId(), token))
                .map(MemoryIndexOutboxEntity::toDomain);
    }

    @Override
    public boolean complete(String eventId, String claimToken, Instant completedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(completedAt, "completedAt");
        return mapper.complete(eventId, claimToken, toLocalDateTime(completedAt)) == 1;
    }

    @Override
    public boolean fail(
            String eventId,
            String claimToken,
            String error,
            Instant retryAt
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(retryAt, "retryAt");
        return mapper.fail(eventId, claimToken, error, toLocalDateTime(retryAt)) == 1;
    }

    @Override
    public List<MemoryIndexOutboxEntry> findExpiredClaims(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        QueryWrapper<MemoryIndexOutboxEntity> qw = new QueryWrapper<>();
        qw.eq("status", MemoryIndexOutboxEntry.Status.CLAIMED.name())
          .le("lease_until", toLocalDateTime(now))
          .orderByAsc("lease_until").orderByAsc("id")
          .last("LIMIT " + limit);
        return mapper.selectList(qw).stream()
                .map(MemoryIndexOutboxEntity::toDomain)
                .toList();
    }

    @Override
    public List<MemoryIndexOutboxEntry> findPendingAfterRevision(
            long exclusiveRevision,
            String afterEventId,
            int limit
    ) {
        if (exclusiveRevision < 0) {
            throw new IllegalArgumentException("exclusiveRevision must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        QueryWrapper<MemoryIndexOutboxEntity> qw = new QueryWrapper<>();
        qw.ne("status", MemoryIndexOutboxEntry.Status.SUCCEEDED.name())
          .and(wrapper -> wrapper
                  .gt("index_revision", exclusiveRevision)
                  .or(afterEventId != null && !afterEventId.isBlank(),
                          sameRevision -> sameRevision
                                  .eq("index_revision", exclusiveRevision)
                                  .gt("event_id", afterEventId.trim())))
          .orderByAsc("index_revision").orderByAsc("event_id")
          .last("LIMIT " + limit);
        return mapper.selectList(qw).stream()
                .map(MemoryIndexOutboxEntity::toDomain)
                .toList();
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
