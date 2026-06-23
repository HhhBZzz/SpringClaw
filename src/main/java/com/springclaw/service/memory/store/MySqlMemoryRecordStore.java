package com.springclaw.service.memory.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.springclaw.domain.entity.MemoryRecordEntity;
import com.springclaw.mapper.MemoryRecordMapper;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MySQL 权威 memory_record store。
 *
 * 单 active 版本、版本号唯一、自动来源幂等等不变量由 schema 唯一键强制
 * （uk_memory_single_active、uk_memory_logical_version、uk_memory_version_id、
 * uk_memory_source_policy）。重复插入抛 DuplicateKeyException，由上层视为幂等冲突。
 */
@Component
public class MySqlMemoryRecordStore implements MemoryRecordStore {

    private final MemoryRecordMapper mapper;

    public MySqlMemoryRecordStore(MemoryRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<MemoryRecordVersion> findByVersionId(String memoryVersionId) {
        if (memoryVersionId == null || memoryVersionId.isBlank()) {
            return Optional.empty();
        }
        QueryWrapper<MemoryRecordEntity> qw = new QueryWrapper<>();
        qw.eq("memory_version_id", memoryVersionId).eq("deleted", 0);
        return Optional.ofNullable(mapper.selectOne(qw))
                .map(MemoryRecordEntity::toDomain);
    }

    @Override
    public Optional<MemoryRecordVersion> findActive(String logicalMemoryId) {
        if (logicalMemoryId == null || logicalMemoryId.isBlank()) {
            return Optional.empty();
        }
        QueryWrapper<MemoryRecordEntity> qw = new QueryWrapper<>();
        qw.eq("logical_memory_id", logicalMemoryId)
          .eq("status", MemoryStatus.ACTIVE.name())
          .eq("active_slot", 1)
          .eq("deleted", 0);
        return Optional.ofNullable(mapper.selectOne(qw))
                .map(MemoryRecordEntity::toDomain);
    }

    @Override
    public Optional<MemoryRecordVersion> findByAutomaticSource(
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion,
            MemoryType memoryType
    ) {
        if (sourceKind == null || sourceKind.isBlank()
                || sourceIdentity == null || sourceIdentity.isBlank()
                || extractionPolicyVersion == null
                || extractionPolicyVersion.isBlank()
                || memoryType == null) {
            return Optional.empty();
        }
        QueryWrapper<MemoryRecordEntity> qw = new QueryWrapper<>();
        qw.eq("source_kind", sourceKind.trim())
          .eq("source_identity", sourceIdentity.trim())
          .eq("extraction_policy_version", extractionPolicyVersion.trim())
          .eq("memory_type", memoryType.name())
          .eq("deleted", 0);
        return Optional.ofNullable(mapper.selectOne(qw))
                .map(MemoryRecordEntity::toDomain);
    }

    @Override
    public Optional<MemoryRecordVersion> findByAutomaticSourceCurrent(
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion,
            MemoryType memoryType
    ) {
        if (sourceKind == null || sourceKind.isBlank()
                || sourceIdentity == null || sourceIdentity.isBlank()
                || extractionPolicyVersion == null
                || extractionPolicyVersion.isBlank()
                || memoryType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByAutomaticSourceForUpdate(
                        sourceKind.trim(),
                        sourceIdentity.trim(),
                        extractionPolicyVersion.trim(),
                        memoryType.name()
                ))
                .map(MemoryRecordEntity::toDomain);
    }

    @Override
    public List<MemoryRecordVersion> findActiveByScope(
            MemoryScope scope,
            Set<MemoryType> types,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        QueryWrapper<MemoryRecordEntity> qw = new QueryWrapper<>();
        qw.eq("scope_type", scope.scopeType().name())
          .eq("scope_id", scope.scopeId())
          .eq("status", MemoryStatus.ACTIVE.name())
          .eq("deleted", 0);
        if (types != null && !types.isEmpty()) {
            qw.in("memory_type", types.stream().map(Enum::name).toList());
        }
        qw.orderByDesc("update_time").orderByDesc("memory_version_id");
        qw.last("LIMIT " + limit);
        return mapper.selectList(qw).stream()
                .map(MemoryRecordEntity::toDomain)
                .toList();
    }

    @Override
    public void insert(MemoryRecordVersion version) {
        // 重复由 schema 唯一键强制，抛 DuplicateKeyException 供上层识别幂等冲突。
        mapper.insert(MemoryRecordEntity.fromDomain(version));
    }

    @Override
    public boolean compareAndSetStatus(
            String memoryVersionId,
            MemoryStatus expected,
            MemoryStatus next,
            Integer nextActiveSlot,
            long expectedIndexRevision,
            long nextIndexRevision,
            Instant updatedAt
    ) {
        return mapper.compareAndSetStatus(
                memoryVersionId,
                expected.name(),
                next.name(),
                nextActiveSlot,
                expectedIndexRevision,
                nextIndexRevision,
                toLocalDateTime(updatedAt)
        ) == 1;
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
