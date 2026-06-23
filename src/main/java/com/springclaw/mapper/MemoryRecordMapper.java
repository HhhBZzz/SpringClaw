package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.MemoryRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * memory_record Mapper。CAS / active-slot 语义由 store 层通过显式 SQL 控制。
 */
public interface MemoryRecordMapper extends BaseMapper<MemoryRecordEntity> {

    @Select("SELECT * FROM memory_record " +
            "WHERE source_kind = #{sourceKind} " +
            "AND source_identity = #{sourceIdentity} " +
            "AND extraction_policy_version = #{extractionPolicyVersion} " +
            "AND memory_type = #{memoryType} " +
            "AND deleted = 0 " +
            "FOR UPDATE")
    MemoryRecordEntity selectByAutomaticSourceForUpdate(
            @Param("sourceKind") String sourceKind,
            @Param("sourceIdentity") String sourceIdentity,
            @Param("extractionPolicyVersion") String extractionPolicyVersion,
            @Param("memoryType") String memoryType
    );

    @Update("UPDATE memory_record " +
            "SET status = #{nextStatus}, active_slot = #{nextActiveSlot}, " +
            "    index_revision = #{nextIndexRevision}, update_time = #{updatedAt} " +
            "WHERE memory_version_id = #{memoryVersionId} " +
            "AND status = #{expectedStatus} " +
            "AND index_revision = #{expectedIndexRevision} " +
            "AND deleted = 0")
    int compareAndSetStatus(@Param("memoryVersionId") String memoryVersionId,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("nextStatus") String nextStatus,
                            @Param("nextActiveSlot") Integer nextActiveSlot,
                            @Param("expectedIndexRevision") long expectedIndexRevision,
                            @Param("nextIndexRevision") long nextIndexRevision,
                            @Param("updatedAt") LocalDateTime updatedAt);
}
