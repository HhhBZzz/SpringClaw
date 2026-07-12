package com.springclaw.service.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MySQL-backed workspace mutation lease store.
 * All expiry decisions use the database clock so multiple application nodes agree.
 */
@Component
public class WorkspaceMutationLeaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceMutationLeaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Optional<WorkspaceMutationLease> acquire(String workspaceId,
                                                    String proposalId,
                                                    Duration ttl) {
        long ttlSeconds = positiveSeconds(ttl);
        jdbcTemplate.update(
                "INSERT IGNORE INTO workspace_mutation_lease " +
                        "(workspace_id, holder_proposal_id, fencing_token, lease_until, update_time) " +
                        "VALUES (?, NULL, 0, NULL, CURRENT_TIMESTAMP(6))",
                workspaceId);

        LeaseRow current = lockRow(workspaceId);
        boolean active = current.holderProposalId() != null
                && current.leaseUntil() != null
                && current.leaseUntil().isAfter(current.databaseNow());
        if (active) {
            return Optional.empty();
        }

        long nextToken = Math.addExact(current.fencingToken(), 1L);
        int updated = jdbcTemplate.update(
                "UPDATE workspace_mutation_lease " +
                        "SET holder_proposal_id = ?, fencing_token = ?, " +
                        "lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), " +
                        "update_time = CURRENT_TIMESTAMP(6) " +
                        "WHERE workspace_id = ?",
                proposalId, nextToken, ttlSeconds, workspaceId);
        if (updated != 1) {
            throw new IllegalStateException("工作区租约行更新失败: " + workspaceId);
        }
        return Optional.of(readLease(workspaceId));
    }

    public boolean renewForCommit(String workspaceId,
                                  String proposalId,
                                  long fencingToken,
                                  Duration ttl) {
        long ttlSeconds = positiveSeconds(ttl);
        return jdbcTemplate.update(
                "UPDATE workspace_mutation_lease " +
                        "SET lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), " +
                        "update_time = CURRENT_TIMESTAMP(6) " +
                        "WHERE workspace_id = ? AND holder_proposal_id = ? AND fencing_token = ? " +
                        "AND lease_until > CURRENT_TIMESTAMP(6)",
                ttlSeconds, workspaceId, proposalId, fencingToken) == 1;
    }

    public boolean release(String workspaceId, String proposalId, long fencingToken) {
        return jdbcTemplate.update(
                "UPDATE workspace_mutation_lease " +
                        "SET holder_proposal_id = NULL, lease_until = NULL, update_time = CURRENT_TIMESTAMP(6) " +
                        "WHERE workspace_id = ? AND holder_proposal_id = ? AND fencing_token = ?",
                workspaceId, proposalId, fencingToken) == 1;
    }

    private LeaseRow lockRow(String workspaceId) {
        List<LeaseRow> rows = jdbcTemplate.query(
                "SELECT workspace_id, holder_proposal_id, fencing_token, lease_until, " +
                        "CURRENT_TIMESTAMP(6) AS database_now " +
                        "FROM workspace_mutation_lease WHERE workspace_id = ? FOR UPDATE",
                (rs, rowNum) -> new LeaseRow(
                        rs.getString("workspace_id"),
                        rs.getString("holder_proposal_id"),
                        rs.getLong("fencing_token"),
                        rs.getObject("lease_until", LocalDateTime.class),
                        rs.getObject("database_now", LocalDateTime.class)),
                workspaceId);
        if (rows.size() != 1) {
            throw new IllegalStateException("工作区租约行不存在: " + workspaceId);
        }
        return rows.get(0);
    }

    private WorkspaceMutationLease readLease(String workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT workspace_id, holder_proposal_id, fencing_token, lease_until " +
                        "FROM workspace_mutation_lease WHERE workspace_id = ?",
                (rs, rowNum) -> new WorkspaceMutationLease(
                        rs.getString("workspace_id"),
                        rs.getString("holder_proposal_id"),
                        rs.getLong("fencing_token"),
                        rs.getObject("lease_until", LocalDateTime.class)),
                workspaceId);
    }

    private long positiveSeconds(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lease ttl 必须大于 0");
        }
        return Math.max(1L, ttl.toSeconds());
    }

    private record LeaseRow(
            String workspaceId,
            String holderProposalId,
            long fencingToken,
            LocalDateTime leaseUntil,
            LocalDateTime databaseNow
    ) { }
}
