package com.springclaw.service.proposal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.springclaw.domain.entity.ToolInvocationProposalEntity;
import com.springclaw.mapper.ToolInvocationProposalMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ToolInvocationProposal 数据访问门面。
 *
 * 设计动机：上层 ProposalService（Task 7）只看见 record 类型，
 * 不被 MyBatis-Plus 实体污染。这里集中：
 *   - record &lt;-&gt; entity 的双向转换
 *   - 乐观锁 CAS 调用
 *   - 终态写入 / 过期清理 / 卡死回收 等定制 SQL
 *
 * 不使用 ServiceImpl，避免在调用方暴露 entity。
 */
@Component
public class ToolInvocationProposalRepository {

    private final ToolInvocationProposalMapper mapper;

    public ToolInvocationProposalRepository(ToolInvocationProposalMapper mapper) {
        this.mapper = mapper;
    }

    public ToolInvocationProposal insert(ToolInvocationProposal proposal) {
        ToolInvocationProposalEntity entity = ToolInvocationProposalEntity.fromDomain(proposal);
        mapper.insert(entity);
        return entity.toDomain();
    }

    public Optional<ToolInvocationProposal> findByProposalId(String proposalId) {
        QueryWrapper<ToolInvocationProposalEntity> qw = new QueryWrapper<>();
        qw.eq("proposal_id", proposalId).eq("deleted", 0);
        return Optional.ofNullable(mapper.selectOne(qw)).map(ToolInvocationProposalEntity::toDomain);
    }

    public List<ToolInvocationProposal> listBy(String sessionKey, String status) {
        QueryWrapper<ToolInvocationProposalEntity> qw = new QueryWrapper<>();
        qw.eq("deleted", 0);
        if (sessionKey != null && !sessionKey.isBlank()) {
            qw.eq("session_key", sessionKey);
        }
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        qw.orderByDesc("id");
        return mapper.selectList(qw).stream().map(ToolInvocationProposalEntity::toDomain).toList();
    }

    public boolean compareAndSetStatus(String proposalId,
                                       ToolInvocationProposalStatus from,
                                       ToolInvocationProposalStatus to,
                                       int expectedVersion,
                                       String reviewReason) {
        return mapper.compareAndSetStatus(
                proposalId, from.name(), to.name(),
                expectedVersion, reviewReason, LocalDateTime.now()
        ) == 1;
    }

    /**
     * 将过期且仍为 PENDING 的授权单逐行 CAS 迁移到 EXPIRED，返回本次成功迁移的精确列表。
     *
     * <p>使用 per-row 乐观锁（version）而非批量 UPDATE，确保并发 confirm 不会把已被确认
     * 的授权单误报为过期。只有真正把某行从 PENDING 迁移到 EXPIRED 的 CAS 才计入返回列表。
     */
    public List<ToolInvocationProposal> expirePendingBefore(LocalDateTime threshold) {
        QueryWrapper<ToolInvocationProposalEntity> qw = new QueryWrapper<>();
        qw.eq("status", ToolInvocationProposalStatus.PENDING.name())
          .lt("expires_at", threshold)
          .eq("deleted", 0);
        List<ToolInvocationProposalEntity> expired = mapper.selectList(qw);
        if (expired.isEmpty()) {
            return List.of();
        }
        java.util.List<ToolInvocationProposal> expiredProposals = new java.util.ArrayList<>();
        for (ToolInvocationProposalEntity entity : expired) {
            int version = entity.getVersion() == null ? 0 : entity.getVersion();
            boolean changed = compareAndSetStatus(
                    entity.getProposalId(),
                    ToolInvocationProposalStatus.PENDING,
                    ToolInvocationProposalStatus.EXPIRED,
                    version,
                    null
            );
            if (changed) {
                expiredProposals.add(entity.toDomain());
            }
        }
        return expiredProposals;
    }

    public List<ToolInvocationProposal> findStuckExecuting(LocalDateTime updatedBefore) {
        QueryWrapper<ToolInvocationProposalEntity> qw = new QueryWrapper<>();
        qw.eq("status", ToolInvocationProposalStatus.EXECUTING.name())
          .lt("update_time", updatedBefore)
          .eq("deleted", 0);
        return mapper.selectList(qw).stream().map(ToolInvocationProposalEntity::toDomain).toList();
    }

    /**
     * 终态写入：仅在 status==EXECUTING 时把成功提交结果落盘并迁移至 EXECUTED。
     */
    public boolean recordCommit(String proposalId, String commitSha, List<String> changedFiles, String executionResult) {
        UpdateWrapper<ToolInvocationProposalEntity> uw = new UpdateWrapper<>();
        uw.eq("proposal_id", proposalId)
          .eq("status", ToolInvocationProposalStatus.EXECUTING.name());
        ToolInvocationProposalEntity update = new ToolInvocationProposalEntity();
        update.setGitCommitSha(commitSha);
        update.setGitChangedFiles(ToolInvocationProposalEntity.encodeList(changedFiles));
        update.setExecutionResult(executionResult);
        update.setExecutedAt(LocalDateTime.now());
        update.setStatus(ToolInvocationProposalStatus.EXECUTED.name());
        update.setUpdateTime(LocalDateTime.now());
        return mapper.update(update, uw) == 1;
    }

    public boolean recordFailure(String proposalId, String error) {
        UpdateWrapper<ToolInvocationProposalEntity> uw = new UpdateWrapper<>();
        uw.eq("proposal_id", proposalId)
          .in("status",
              ToolInvocationProposalStatus.EXECUTING.name(),
              ToolInvocationProposalStatus.APPROVED.name());
        ToolInvocationProposalEntity update = new ToolInvocationProposalEntity();
        update.setStatus(ToolInvocationProposalStatus.FAILED.name());
        String trimmed = error == null ? "" : (error.length() > 1024 ? error.substring(0, 1024) : error);
        update.setExecutionError(trimmed);
        update.setUpdateTime(LocalDateTime.now());
        return mapper.update(update, uw) == 1;
    }

    public boolean recordBaseline(String proposalId, String baselineSha) {
        UpdateWrapper<ToolInvocationProposalEntity> uw = new UpdateWrapper<>();
        uw.eq("proposal_id", proposalId)
          .in("status",
              ToolInvocationProposalStatus.PENDING.name(),
              ToolInvocationProposalStatus.APPROVED.name(),
              ToolInvocationProposalStatus.EXECUTING.name());
        ToolInvocationProposalEntity update = new ToolInvocationProposalEntity();
        update.setGitBaselineSha(baselineSha);
        update.setUpdateTime(LocalDateTime.now());
        return mapper.update(update, uw) == 1;
    }
}
