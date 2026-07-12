package com.springclaw.service.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class WorkspaceMutationLeaseRepositoryTest {

    @Autowired
    WorkspaceMutationLeaseRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void acquireReturnsFirstTokenAndRejectsActiveCompetitor() {
        String workspaceId = workspaceId();

        WorkspaceMutationLease first = repository.acquire(
                workspaceId, "proposal-1", Duration.ofMinutes(5)).orElseThrow();
        Optional<WorkspaceMutationLease> competitor = repository.acquire(
                workspaceId, "proposal-2", Duration.ofMinutes(5));

        assertThat(first.workspaceId()).isEqualTo(workspaceId);
        assertThat(first.proposalId()).isEqualTo("proposal-1");
        assertThat(first.fencingToken()).isEqualTo(1L);
        assertThat(first.leaseUntil()).isNotNull();
        assertThat(competitor).isEmpty();
    }

    @Test
    void expiredLeaseGetsHigherTokenAndOldTokenCannotRenewOrReleaseIt() {
        String workspaceId = workspaceId();
        WorkspaceMutationLease first = repository.acquire(
                workspaceId, "proposal-old", Duration.ofMinutes(5)).orElseThrow();
        jdbcTemplate.update(
                "UPDATE workspace_mutation_lease " +
                        "SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) " +
                        "WHERE workspace_id = ?",
                workspaceId);

        WorkspaceMutationLease replacement = repository.acquire(
                workspaceId, "proposal-new", Duration.ofMinutes(5)).orElseThrow();

        assertThat(replacement.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(repository.renewForCommit(
                workspaceId, first.proposalId(), first.fencingToken(), Duration.ofSeconds(30))).isFalse();
        assertThat(repository.release(
                workspaceId, first.proposalId(), first.fencingToken())).isFalse();
        assertThat(repository.renewForCommit(
                workspaceId, replacement.proposalId(), replacement.fencingToken(), Duration.ofSeconds(30))).isTrue();

        String holder = jdbcTemplate.queryForObject(
                "SELECT holder_proposal_id FROM workspace_mutation_lease WHERE workspace_id = ?",
                String.class,
                workspaceId);
        assertThat(holder).isEqualTo("proposal-new");
        assertThat(repository.release(
                workspaceId, replacement.proposalId(), replacement.fencingToken())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT holder_proposal_id IS NULL FROM workspace_mutation_lease WHERE workspace_id = ?",
                Boolean.class,
                workspaceId)).isTrue();
    }

    private String workspaceId() {
        return UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "");
    }
}
