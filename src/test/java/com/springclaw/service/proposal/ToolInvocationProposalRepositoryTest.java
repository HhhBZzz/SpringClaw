package com.springclaw.service.proposal;

import com.springclaw.domain.entity.ToolInvocationProposalEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolInvocationProposalRepository 集成测试。
 *
 * 守住 P0 不变量：
 *   - 1：状态 + version 双校验的乐观锁（compareAndSetStatus）
 *   - 2：record &lt;-&gt; entity 双向无损（含 JSON 数组 / enum）
 *   - 8：过期清理只迁移 PENDING + 过期窗口内的授权单
 *   - 9：listBy 按 sessionKey + status 精确过滤
 *
 * 使用与 ToolRuntimeAspectInterceptionIT 相同的启动属性，保证不依赖真实 LLM。
 */
@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class ToolInvocationProposalRepositoryTest {

    @Autowired
    ToolInvocationProposalRepository repository;

    @Test
    void insertThenFindByProposalId_returnsRecordWithSameFields() {
        String proposalId = "p-" + UUID.randomUUID();
        ToolInvocationProposal p = sampleProposal(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                LocalDateTime.now().plusHours(1)
        );

        repository.insert(p);

        Optional<ToolInvocationProposal> foundOpt = repository.findByProposalId(proposalId);
        assertThat(foundOpt).isPresent();
        ToolInvocationProposal found = foundOpt.get();

        assertThat(found.proposalId()).isEqualTo(proposalId);
        assertThat(found.requestId()).isEqualTo("req-" + proposalId);
        assertThat(found.sessionKey()).isEqualTo("session-A");
        assertThat(found.userId()).isEqualTo("user-1");
        assertThat(found.roleCode()).isEqualTo("USER");
        assertThat(found.toolName()).isEqualTo("WorkspaceEditToolPack.workspaceWriteFile");
        assertThat(found.toolsetId()).isEqualTo("workspace");
        assertThat(found.argumentsCanonicalJson()).isEqualTo("[\"path/A.java\", \"hello\"]");
        assertThat(found.argumentsHash()).startsWith("deadbeef");
        assertThat(found.riskLevel()).isEqualTo("write");
        assertThat(found.targetPaths()).containsExactly("src/A.java", "src/B.java");
        assertThat(found.previewSummary()).isEqualTo("preview");
        assertThat(found.workspaceDirtyAtCreate()).isFalse();
        assertThat(found.dirtyFilesAtCreate()).isEmpty();
        assertThat(found.status()).isEqualTo(ToolInvocationProposalStatus.PENDING);
        assertThat(found.version()).isEqualTo(0);
        assertThat(found.gitHeadShaAtCreate()).isEqualTo("abc1234");
        assertThat(found.gitChangedFiles()).isEmpty();
        assertThat(found.id()).isNotNull();
    }

    @Test
    void compareAndSetStatus_succeedsWhenStateMatches() {
        String proposalId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                LocalDateTime.now().plusHours(1)
        ));

        boolean ok = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                ToolInvocationProposalStatus.APPROVED,
                0,
                "user-approved"
        );

        assertThat(ok).isTrue();
        ToolInvocationProposal after = repository.findByProposalId(proposalId).orElseThrow();
        assertThat(after.status()).isEqualTo(ToolInvocationProposalStatus.APPROVED);
        assertThat(after.version()).isEqualTo(1);
    }

    @Test
    void compareAndSetStatus_failsWhenVersionMismatch() {
        String proposalId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                LocalDateTime.now().plusHours(1)
        ));

        boolean ok = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                ToolInvocationProposalStatus.APPROVED,
                99,
                "stale-version"
        );

        assertThat(ok).isFalse();
        ToolInvocationProposal after = repository.findByProposalId(proposalId).orElseThrow();
        assertThat(after.status()).isEqualTo(ToolInvocationProposalStatus.PENDING);
        assertThat(after.version()).isEqualTo(0);
    }

    @Test
    void expirePendingBefore_marksOnlyExpiredPending() {
        String oldId = "p-" + UUID.randomUUID();
        String freshId = "p-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        repository.insert(sampleProposal(oldId, ToolInvocationProposalStatus.PENDING, now.minusSeconds(1)));
        repository.insert(sampleProposal(freshId, ToolInvocationProposalStatus.PENDING, now.plusHours(1)));

        List<ToolInvocationProposal> expired = repository.expirePendingBefore(now);

        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).proposalId()).isEqualTo(oldId);
        assertThat(repository.findByProposalId(oldId).orElseThrow().status())
                .isEqualTo(ToolInvocationProposalStatus.EXPIRED);
        assertThat(repository.findByProposalId(freshId).orElseThrow().status())
                .isEqualTo(ToolInvocationProposalStatus.PENDING);
    }

    @Test
    void listBy_filtersBySessionAndStatus() {
        LocalDateTime exp = LocalDateTime.now().plusHours(1);
        String aPending = "p-" + UUID.randomUUID();
        String aApproved = "p-" + UUID.randomUUID();
        String bPending = "p-" + UUID.randomUUID();

        repository.insert(withSession(sampleProposal(aPending, ToolInvocationProposalStatus.PENDING, exp), "session-A"));
        repository.insert(withSession(sampleProposal(aApproved, ToolInvocationProposalStatus.APPROVED, exp), "session-A"));
        repository.insert(withSession(sampleProposal(bPending, ToolInvocationProposalStatus.PENDING, exp), "session-B"));

        List<ToolInvocationProposal> aPendings = repository.listBy("session-A", "PENDING");

        assertThat(aPendings).extracting(ToolInvocationProposal::proposalId)
                .contains(aPending)
                .doesNotContain(aApproved, bPending);
    }

    @Test
    void recordFailureRejectsTerminalStatus() {
        // 终态 EXECUTED 不应被 recordFailure 改写。
        String executedId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                executedId,
                ToolInvocationProposalStatus.EXECUTED,
                LocalDateTime.now().plusHours(1)
        ));

        boolean failedOnExecuted = repository.recordFailure(executedId, "boom");

        assertThat(failedOnExecuted).isFalse();
        ToolInvocationProposal stillExecuted = repository.findByProposalId(executedId).orElseThrow();
        assertThat(stillExecuted.status()).isEqualTo(ToolInvocationProposalStatus.EXECUTED);
        assertThat(stillExecuted.executionError()).isNull();

        // EXECUTING -> FAILED 是合法转移。
        String executingId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                executingId,
                ToolInvocationProposalStatus.EXECUTING,
                LocalDateTime.now().plusHours(1)
        ));

        boolean failedOnExecuting = repository.recordFailure(executingId, "boom");

        assertThat(failedOnExecuting).isTrue();
        ToolInvocationProposal nowFailed = repository.findByProposalId(executingId).orElseThrow();
        assertThat(nowFailed.status()).isEqualTo(ToolInvocationProposalStatus.FAILED);
        assertThat(nowFailed.executionError()).isEqualTo("boom");
    }

    @Test
    void recordBaselineRejectsTerminalStatus() {
        // 终态 FAILED 不应被 recordBaseline 改写——baseline 只在执行前/中有意义。
        String failedId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                failedId,
                ToolInvocationProposalStatus.FAILED,
                LocalDateTime.now().plusHours(1)
        ));

        boolean updatedOnFailed = repository.recordBaseline(failedId, "abc1234");

        assertThat(updatedOnFailed).isFalse();
        ToolInvocationProposal stillFailed = repository.findByProposalId(failedId).orElseThrow();
        assertThat(stillFailed.gitBaselineSha()).isNull();

        // APPROVED 状态可写 baseline（覆盖 Aspect 早于 executor 设置 EXECUTING 的竞态）。
        String approvedId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                approvedId,
                ToolInvocationProposalStatus.APPROVED,
                LocalDateTime.now().plusHours(1)
        ));

        boolean updatedOnApproved = repository.recordBaseline(approvedId, "abc1234");

        assertThat(updatedOnApproved).isTrue();
        ToolInvocationProposal afterBaseline = repository.findByProposalId(approvedId).orElseThrow();
        assertThat(afterBaseline.gitBaselineSha()).isEqualTo("abc1234");
    }

    @Test
    void compareAndSetStatusStampsReviewedAtWhenReasonSupplied() {
        String proposalId = "p-" + UUID.randomUUID();
        repository.insert(sampleProposal(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                LocalDateTime.now().plusHours(1)
        ));

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        boolean approved = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.PENDING,
                ToolInvocationProposalStatus.APPROVED,
                0,
                "用户确认"
        );
        assertThat(approved).isTrue();

        ToolInvocationProposal afterApprove = repository.findByProposalId(proposalId).orElseThrow();
        assertThat(afterApprove.reviewReason()).isEqualTo("用户确认");
        assertThat(afterApprove.reviewedAt()).isNotNull();
        assertThat(afterApprove.reviewedAt()).isAfterOrEqualTo(before);
        assertThat(afterApprove.reviewedAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(5));

        LocalDateTime stamped = afterApprove.reviewedAt();

        // 系统级 CAS（reviewReason=null）不应重新打 reviewed_at 戳。
        boolean executingMoved = repository.compareAndSetStatus(
                proposalId,
                ToolInvocationProposalStatus.APPROVED,
                ToolInvocationProposalStatus.EXECUTING,
                1,
                null
        );
        assertThat(executingMoved).isTrue();

        ToolInvocationProposal afterExecuting = repository.findByProposalId(proposalId).orElseThrow();
        assertThat(afterExecuting.status()).isEqualTo(ToolInvocationProposalStatus.EXECUTING);
        assertThat(afterExecuting.reviewedAt()).isEqualTo(stamped);
        assertThat(afterExecuting.reviewReason()).isEqualTo("用户确认");
    }

    private ToolInvocationProposal sampleProposal(String proposalId,
                                                  ToolInvocationProposalStatus status,
                                                  LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        String hashSuffix = proposalId.length() >= 8 ? proposalId.substring(0, 8) : proposalId;
        return new ToolInvocationProposal(
                null, proposalId, "req-" + proposalId, null,
                "session-A", "user-1", "USER",
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                "[\"path/A.java\", \"hello\"]", "deadbeef" + hashSuffix,
                "write", List.of("src/A.java", "src/B.java"), "preview",
                false, List.of(),
                status, 0,
                null, null, null,
                "abc1234", null, null, List.of(),
                null, null,
                now, now, expiresAt
        );
    }

    private ToolInvocationProposal withSession(ToolInvocationProposal p, String sessionKey) {
        return new ToolInvocationProposal(
                p.id(), p.proposalId(), p.requestId(), p.runId(),
                sessionKey, p.userId(), p.roleCode(),
                p.toolName(), p.toolsetId(), p.argumentsCanonicalJson(), p.argumentsHash(),
                p.riskLevel(), p.targetPaths(), p.previewSummary(),
                p.workspaceDirtyAtCreate(), p.dirtyFilesAtCreate(),
                p.status(), p.version(),
                p.executedAt(), p.executionResult(), p.executionError(),
                p.gitHeadShaAtCreate(), p.gitBaselineSha(), p.gitCommitSha(), p.gitChangedFiles(),
                p.reviewedAt(), p.reviewReason(),
                p.createTime(), p.updateTime(), p.expiresAt()
        );
    }

    /** Helper kept for IDE jump-to-source from entity coverage; unused at runtime. */
    @SuppressWarnings("unused")
    private static ToolInvocationProposalEntity entityRef() {
        return null;
    }
}
