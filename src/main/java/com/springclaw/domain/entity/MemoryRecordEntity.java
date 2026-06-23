package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * memory_record 表实体。
 *
 * 不继承 BaseEntity——memory version 的时间戳参与 CAS/active-slot 语义，
 * 必须由 store 显式控制，不能让 MyBatis-Plus 的 FieldFill 偷偷改 update_time。
 *
 * JSON 数组字段（source_event_ids_json、evidence_refs_json、tags_json）以 TEXT 落库，
 * 由 fromDomain / toDomain 显式编解码，腐烂时 fail loud（provenance 字段不容静默归零）。
 */
@Data
@TableName("memory_record")
public class MemoryRecordEntity implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

    @TableId(type = IdType.AUTO)
    private Long id;

    private String logicalMemoryId;
    private String memoryVersionId;
    private String memoryType;
    private String scopeType;
    private String scopeId;
    private String ownerUserId;
    private String content;
    private String contentHash;
    private String summary;
    private String sourceRunId;
    private String sourceEventIdsJson;
    private String evidenceRefsJson;
    private String tagsJson;
    private java.math.BigDecimal importance;
    private java.math.BigDecimal confidence;
    private String status;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private Long supersedesRecordId;
    private Integer version;
    private Integer activeSlot;
    private String sourceKind;
    private String sourceIdentity;
    private String extractionPolicyVersion;
    private Long indexRevision;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableField("deleted")
    private Integer deleted;

    public static MemoryRecordEntity fromDomain(MemoryRecordVersion v) {
        MemoryRecordEntity e = new MemoryRecordEntity();
        e.id = v.recordId();
        e.logicalMemoryId = v.logicalMemoryId();
        e.memoryVersionId = v.memoryVersionId();
        e.memoryType = v.memoryType().name();
        e.scopeType = v.scopeType().name();
        e.scopeId = v.scopeId();
        e.ownerUserId = v.ownerUserId();
        e.content = v.content();
        e.contentHash = v.contentHash();
        e.summary = v.summary();
        e.sourceRunId = v.sourceRunId();
        e.sourceEventIdsJson = encodeList(v.sourceEventIds());
        e.evidenceRefsJson = encodeList(v.evidenceRefs());
        e.tagsJson = encodeList(v.tags());
        e.importance = java.math.BigDecimal.valueOf(v.importance());
        e.confidence = java.math.BigDecimal.valueOf(v.confidence());
        e.status = v.status().name();
        e.validFrom = toLocalDateTime(v.validFrom());
        e.validUntil = toLocalDateTime(v.validUntil());
        e.supersedesRecordId = v.supersedesRecordId();
        e.version = v.version();
        e.activeSlot = v.activeSlot();
        e.sourceKind = v.sourceKind();
        e.sourceIdentity = v.sourceIdentity();
        e.extractionPolicyVersion = v.extractionPolicyVersion();
        e.indexRevision = v.indexRevision();
        e.createTime = toLocalDateTime(v.createdAt());
        e.updateTime = toLocalDateTime(v.updatedAt());
        e.deleted = v.deleted() ? 1 : 0;
        return e;
    }

    public MemoryRecordVersion toDomain() {
        return new MemoryRecordVersion(
                id,
                logicalMemoryId,
                memoryVersionId,
                MemoryType.valueOf(memoryType),
                MemoryScopeType.valueOf(scopeType),
                scopeId,
                ownerUserId,
                content,
                contentHash,
                summary,
                sourceRunId,
                decodeList(sourceEventIdsJson),
                decodeList(evidenceRefsJson),
                decodeList(tagsJson),
                importance == null ? 0.0 : importance.doubleValue(),
                confidence == null ? 0.0 : confidence.doubleValue(),
                MemoryStatus.valueOf(status),
                fromLocalDateTime(validFrom),
                fromLocalDateTime(validUntil),
                supersedesRecordId,
                version == null ? 0 : version,
                activeSlot,
                sourceKind,
                sourceIdentity,
                extractionPolicyVersion,
                indexRevision == null ? 0L : indexRevision,
                fromLocalDateTime(createTime),
                fromLocalDateTime(updateTime),
                deleted != null && deleted == 1
        );
    }

    public static String encodeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception ex) {
            throw new IllegalStateException("encode JSON array failed", ex);
        }
    }

    public static List<String> decodeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_OF_STRING);
        } catch (Exception ex) {
            // provenance 字段腐烂不应静默归零，否则记忆溯源与权限校验会失真。Fail loud。
            throw new IllegalStateException("decode JSON array failed: " + json, ex);
        }
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromLocalDateTime(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }
}
