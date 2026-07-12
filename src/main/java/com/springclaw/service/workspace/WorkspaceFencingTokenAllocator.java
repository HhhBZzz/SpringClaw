package com.springclaw.service.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Allocates durable, globally monotonic fencing tokens in an independent transaction. */
@Component
public class WorkspaceFencingTokenAllocator {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceFencingTokenAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextToken(String workspaceId, String proposalId) {
        if (workspaceId == null || workspaceId.isBlank()
                || proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 和 proposalId 不能为空");
        }
        int updated = jdbcTemplate.update(
                "UPDATE workspace_fencing_token_counter " +
                        "SET last_token = LAST_INSERT_ID(last_token + 1), " +
                        "update_time = CURRENT_TIMESTAMP(6) WHERE counter_id = 1");
        if (updated != 1) {
            throw new IllegalStateException("fencing token counter 不存在");
        }
        Long token = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (token == null || token <= 0) {
            throw new IllegalStateException("fencing token 分配失败");
        }
        return token;
    }
}
