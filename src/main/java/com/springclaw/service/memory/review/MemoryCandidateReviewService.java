package com.springclaw.service.memory.review;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.consolidation.MemoryConsolidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class MemoryCandidateReviewService {

    private static final Logger log = LoggerFactory.getLogger(MemoryCandidateReviewService.class);

    private final MemoryRecordStore recordStore;
    private final MemoryManagementService memoryManagementService;
    private final MemoryConsolidationService consolidationService;
    private final Clock clock;
    private final boolean consolidationEnabled;
    private final int consolidationEpisodeLimit;

    public MemoryCandidateReviewService(
            MemoryRecordStore recordStore,
            MemoryManagementService memoryManagementService,
            MemoryConsolidationService consolidationService,
            @Value("${springclaw.memory.consolidation.auto-enabled:${SPRINGCLAW_MEMORY_CONSOLIDATION_AUTO_ENABLED:true}}")
            boolean consolidationEnabled,
            @Value("${springclaw.memory.consolidation.auto-episode-limit:${SPRINGCLAW_MEMORY_CONSOLIDATION_AUTO_EPISODE_LIMIT:50}}")
            int consolidationEpisodeLimit,
            ObjectProvider<Clock> clockProvider
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.memoryManagementService = Objects.requireNonNull(memoryManagementService, "memoryManagementService");
        this.consolidationService = Objects.requireNonNull(consolidationService, "consolidationService");
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.consolidationEnabled = consolidationEnabled;
        this.consolidationEpisodeLimit = Math.max(2, Math.min(consolidationEpisodeLimit, 500));
    }

    public List<MemoryCandidateReviewItem> list(String status, int limit) {
        MemoryStatus memoryStatus = parseStatus(
                StringUtils.hasText(status) ? status : MemoryStatus.CANDIDATE.name()
        );
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return recordStore.findByStatus(memoryStatus, safeLimit).stream()
                .map(this::toItem)
                .toList();
    }

    public MemoryCandidateStatusUpdate updateStatus(
            String memoryVersionId,
            String status,
            String reason
    ) {
        if (!StringUtils.hasText(memoryVersionId) || !StringUtils.hasText(status)) {
            throw new IllegalArgumentException("memoryVersionId and status must not be blank");
        }
        MemoryRecordVersion current = recordStore.findByVersionId(memoryVersionId.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "memory version not found: " + memoryVersionId
                ));
        MemoryStatus next = parseStatus(status);
        MemoryRecordVersion updated = memoryManagementService.transition(
                current.memoryVersionId(),
                current.status(),
                next,
                Instant.now(clock)
        );
        maybeConsolidateApprovedEpisode(updated);
        return new MemoryCandidateStatusUpdate(
                updated.memoryVersionId(),
                current.status().name(),
                updated.status().name(),
                reason == null ? "" : reason.trim()
        );
    }

    private void maybeConsolidateApprovedEpisode(MemoryRecordVersion updated) {
        if (!consolidationEnabled
                || updated.memoryType() != MemoryType.EPISODIC
                || updated.status() != MemoryStatus.ACTIVE
                || !StringUtils.hasText(updated.ownerUserId())) {
            return;
        }
        try {
            consolidationService.consolidate(
                    MemoryScope.user("api", "consolidation", updated.ownerUserId()),
                    consolidationEpisodeLimit
            );
        } catch (RuntimeException ex) {
            log.warn("memory consolidation after candidate approval failed, memoryVersionId={}, reason={}",
                    updated.memoryVersionId(), ex.getMessage());
        }
    }

    private MemoryCandidateReviewItem toItem(MemoryRecordVersion record) {
        return new MemoryCandidateReviewItem(
                record.memoryVersionId(),
                record.memoryType().name(),
                record.status().name(),
                record.content(),
                record.summary(),
                record.ownerUserId(),
                record.importance(),
                record.confidence(),
                record.evidenceRefs(),
                record.tags(),
                record.sourceKind(),
                record.sourceIdentity(),
                record.extractionPolicyVersion(),
                record.updatedAt()
        );
    }

    private static MemoryStatus parseStatus(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "APPROVE", "APPROVED", "ENABLE", "ENABLED" -> MemoryStatus.ACTIVE.name();
            case "REJECT", "DISABLE", "DISABLED" -> MemoryStatus.REJECTED.name();
            default -> normalized;
        };
        MemoryStatus status = MemoryStatus.valueOf(normalized);
        if (status != MemoryStatus.CANDIDATE
                && status != MemoryStatus.ACTIVE
                && status != MemoryStatus.REJECTED
                && status != MemoryStatus.EXPIRED
                && status != MemoryStatus.SUPERSEDED) {
            throw new IllegalArgumentException("unsupported memory status: " + raw);
        }
        return status;
    }

    public record MemoryCandidateReviewItem(
            String memoryVersionId,
            String memoryType,
            String status,
            String content,
            String summary,
            String ownerUserId,
            double importance,
            double confidence,
            List<String> evidenceRefs,
            List<String> tags,
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion,
            Instant updatedAt
    ) {
    }

    public record MemoryCandidateStatusUpdate(
            String memoryVersionId,
            String previousStatus,
            String status,
            String reason
    ) {
    }
}
