package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.ToolInvocationProposalEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * tool_invocation_proposal 表 Mapper。
 *
 * BaseMapper 提供 CRUD；自定义 SQL 仅承担乐观锁原子状态迁移：
 * 状态 + version 双校验，避免并发审批 / 重复执行造成的状态错乱。
 */
public interface ToolInvocationProposalMapper extends BaseMapper<ToolInvocationProposalEntity> {

    /**
     * 乐观锁状态迁移：仅当 status==fromStatus 且 version==expectedVersion 时迁移到 toStatus。
     * 同步把 review_reason 写入（如果非 null）。
     * 返回 1 = 成功；0 = 状态/版本被其他请求改写。
     */
    @Update("UPDATE tool_invocation_proposal " +
            "SET status = #{toStatus}, version = version + 1, update_time = #{now}, " +
            "    review_reason = COALESCE(#{reviewReason}, review_reason), " +
            "    reviewed_at = CASE WHEN #{reviewReason} IS NOT NULL THEN #{now} ELSE reviewed_at END " +
            "WHERE proposal_id = #{proposalId} AND status = #{fromStatus} AND version = #{expectedVersion}")
    int compareAndSetStatus(@Param("proposalId") String proposalId,
                            @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus,
                            @Param("expectedVersion") int expectedVersion,
                            @Param("reviewReason") String reviewReason,
                            @Param("now") LocalDateTime now);
}
