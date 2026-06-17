package com.springclaw.scheduled;

import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 每 60 秒清理：
 * <ul>
 *   <li>PENDING 已过期 → EXPIRED（不变量 8）</li>
 *   <li>EXECUTING 卡死 (update_time 超过 10 分钟) → FAILED（不变量 13：不重放，需要新建 proposal）</li>
 * </ul>
 */
@Component
public class ToolProposalCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ToolProposalCleanupTask.class);

    private final ToolInvocationProposalRepository repository;
    private final ToolInvocationProposalService proposalService;

    public ToolProposalCleanupTask(ToolInvocationProposalRepository repository,
                                   ToolInvocationProposalService proposalService) {
        this.repository = repository;
        this.proposalService = proposalService;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();

        // PENDING 超时 → EXPIRED
        int expired = repository.expirePendingBefore(now);
        if (expired > 0) {
            log.info("expired {} stale PENDING proposals", expired);
        }

        // EXECUTING 超过 10 分钟未更新 → FAILED（不变量 13：不重放）
        List<ToolInvocationProposal> stuck = repository.findStuckExecuting(now.minusMinutes(10));
        for (ToolInvocationProposal p : stuck) {
            try {
                proposalService.markFailed(p.proposalId(), "execution interrupted or timeout");
                log.warn("marked stuck EXECUTING proposal {} as FAILED", p.proposalId());
            } catch (Exception ex) {
                log.error("failed to mark stuck EXECUTING proposal {} as FAILED: {}",
                        p.proposalId(), ex.getMessage(), ex);
            }
        }
    }
}
