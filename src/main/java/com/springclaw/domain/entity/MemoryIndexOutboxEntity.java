package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * memory_index_outbox 表实体。
 *
 * 不继承 BaseEntity——outbox 的 claim/lease 时间戳参与租约 fence 语义，
 * 必须由 store 显式控制，不能让 MyBatis-Plus FieldFill 偷偷改 update_time。
 */
@Data
@TableName("memory_index_outbox")
public class MemoryIndexOutboxEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;
    private String logicalMemoryId;
    private String memoryVersionId;
    private Integer memoryVersion;
    private Long indexRevision;
    private String operation;
    private String status;
    private Integer attempts;
    private LocalDateTime availableAt;
    private LocalDateTime claimedAt;
    private String claimOwner;
    private String claimToken;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static MemoryIndexOutboxEntity fromDomain(MemoryIndexOutboxEntry e) {
        MemoryIndexOutboxEntity entity = new MemoryIndexOutboxEntity();
        entity.eventId = e.eventId();
        entity.logicalMemoryId = e.logicalMemoryId();
        entity.memoryVersionId = e.memoryVersionId();
        entity.memoryVersion = e.memoryVersion();
        entity.indexRevision = e.indexRevision();
        entity.operation = e.operation().name();
        entity.status = e.status().name();
        entity.attempts = e.attempts();
        entity.availableAt = toLocalDateTime(e.availableAt());
        entity.claimedAt = toLocalDateTime(e.claimedAt());
        entity.claimOwner = e.claimOwner();
        entity.claimToken = e.claimToken();
        entity.leaseUntil = toLocalDateTime(e.leaseUntil());
        entity.lastError = e.lastError();
        entity.createTime = toLocalDateTime(e.createdAt());
        entity.updateTime = toLocalDateTime(e.updatedAt());
        return entity;
    }

    public MemoryIndexOutboxEntry toDomain() {
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                memoryVersionId,
                memoryVersion == null ? 0 : memoryVersion,
                indexRevision == null ? 0L : indexRevision,
                MemoryIndexOperation.valueOf(operation),
                MemoryIndexOutboxEntry.Status.valueOf(status),
                attempts == null ? 0 : attempts,
                fromLocalDateTime(availableAt),
                fromLocalDateTime(claimedAt),
                claimOwner,
                claimToken,
                fromLocalDateTime(leaseUntil),
                lastError,
                fromLocalDateTime(createTime),
                fromLocalDateTime(updateTime)
        );
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromLocalDateTime(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }
}
