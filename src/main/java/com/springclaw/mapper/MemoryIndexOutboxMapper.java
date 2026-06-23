package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.MemoryIndexOutboxEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * memory_index_outbox Mapper。
 *
 * BaseMapper 提供 CRUD；fenced claim 语义通过两个自定义 SQL 承担：
 *   - selectFencedCandidate：只取同一 logical memory 的最小未完成 revision，且当前可被领取
 *     （PENDING/FAILED 已到 available_at，或 CLAIMED 且租约已过期）。
 *   - claimById：CAS 抢占——仅当候选仍处于可领取状态时迁移到 CLAIMED 并签发新 token。
 */
public interface MemoryIndexOutboxMapper extends BaseMapper<MemoryIndexOutboxEntity> {

    @Select("SELECT * FROM memory_index_outbox o " +
            "WHERE o.index_revision = (" +
            "  SELECT MIN(o2.index_revision) FROM memory_index_outbox o2 " +
            "  WHERE o2.logical_memory_id = o.logical_memory_id " +
            "    AND o2.status IN ('PENDING','FAILED','CLAIMED')" +
            ") " +
            "AND (" +
            "  (o.status IN ('PENDING','FAILED') AND o.available_at <= #{now}) " +
            "  OR (o.status = 'CLAIMED' AND o.lease_until <= #{now})" +
            ") " +
            "ORDER BY o.available_at ASC, o.create_time ASC, o.id ASC " +
            "LIMIT 1")
    MemoryIndexOutboxEntity selectFencedCandidate(@Param("now") LocalDateTime now);

    @Update("UPDATE memory_index_outbox " +
            "SET status = 'CLAIMED', claim_owner = #{owner}, claim_token = #{token}, " +
            "    claimed_at = #{now}, lease_until = #{leaseUntil}, " +
            "    attempts = attempts + 1, last_error = NULL, update_time = #{now} " +
            "WHERE id = #{id} " +
            "AND ( " +
            "  (status IN ('PENDING','FAILED') AND available_at <= #{now}) " +
            "  OR (status = 'CLAIMED' AND lease_until <= #{now})" +
            ") " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM (" +
            "    SELECT logical_memory_id, index_revision, status " +
            "    FROM memory_index_outbox" +
            "  ) lower_o " +
            "  WHERE lower_o.logical_memory_id = #{logicalMemoryId} " +
            "    AND lower_o.status IN ('PENDING','FAILED','CLAIMED') " +
            "    AND lower_o.index_revision < #{indexRevision}" +
            ")")
    int claimById(@Param("id") Long id,
                  @Param("logicalMemoryId") String logicalMemoryId,
                  @Param("indexRevision") Long indexRevision,
                  @Param("owner") String owner,
                  @Param("token") String token,
                  @Param("now") LocalDateTime now,
                  @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("SELECT * FROM memory_index_outbox " +
            "WHERE id = #{id} " +
            "AND status = 'CLAIMED' " +
            "AND claim_token = #{claimToken}")
    MemoryIndexOutboxEntity selectClaimedByIdAndToken(
            @Param("id") Long id,
            @Param("claimToken") String claimToken
    );

    @Update("UPDATE memory_index_outbox " +
            "SET status = 'SUCCEEDED', claim_owner = NULL, claim_token = NULL, " +
            "    claimed_at = NULL, lease_until = NULL, last_error = NULL, update_time = #{completedAt} " +
            "WHERE event_id = #{eventId} AND claim_token = #{claimToken} " +
            "AND status = 'CLAIMED' " +
            "AND lease_until > UTC_TIMESTAMP(3) " +
            "AND lease_until > #{completedAt}")
    int complete(@Param("eventId") String eventId,
                 @Param("claimToken") String claimToken,
                 @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE memory_index_outbox " +
            "SET status = 'FAILED', claim_owner = NULL, claim_token = NULL, " +
            "    claimed_at = NULL, lease_until = NULL, last_error = #{error}, " +
            "    available_at = #{retryAt}, update_time = #{retryAt} " +
            "WHERE event_id = #{eventId} AND claim_token = #{claimToken} " +
            "AND status = 'CLAIMED' " +
            "AND lease_until > UTC_TIMESTAMP(3)")
    int fail(@Param("eventId") String eventId,
             @Param("claimToken") String claimToken,
             @Param("error") String error,
             @Param("retryAt") LocalDateTime retryAt);
}
