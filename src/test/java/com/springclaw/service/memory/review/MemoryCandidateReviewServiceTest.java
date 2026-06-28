package com.springclaw.service.memory.review;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryVersionFactory;
import com.springclaw.service.memory.MemoryWriteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCandidateReviewServiceTest {

    private static final Instant T0 = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final MemoryScope SCOPE = MemoryScope.from(
            SessionAccessClaim.personal(
                    SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    "api",
                    "session-1",
                    "alice"
            )
    );

    private InMemoryMemoryRecordStore recordStore;
    private InMemoryMemoryIndexOutboxStore outboxStore;
    private MemoryManagementService memoryManagementService;
    private MemoryCandidateReviewService reviewService;

    @BeforeEach
    void setUp() {
        recordStore = new InMemoryMemoryRecordStore();
        outboxStore = new InMemoryMemoryIndexOutboxStore(CLOCK);
        memoryManagementService = new MemoryManagementService(
                recordStore,
                outboxStore,
                new MemoryVersionFactory(CLOCK)
        );
        reviewService = new MemoryCandidateReviewService(
                recordStore,
                memoryManagementService,
                new FixedClockProvider()
        );
    }

    @Test
    void listsCandidateMemoryVersionsForReview() {
        var candidate = memoryManagementService.create(command("logical-review-1", MemoryStatus.CANDIDATE));
        memoryManagementService.create(command("logical-review-2", MemoryStatus.ACTIVE));

        List<MemoryCandidateReviewService.MemoryCandidateReviewItem> items =
                reviewService.list("CANDIDATE", 10);

        assertThat(items)
                .extracting(MemoryCandidateReviewService.MemoryCandidateReviewItem::memoryVersionId)
                .containsExactly(candidate.memoryVersionId());
    }

    @Test
    void approvesCandidateAndWritesIndexOutbox() {
        var candidate = memoryManagementService.create(command("logical-review-1", MemoryStatus.CANDIDATE));

        var update = reviewService.updateStatus(
                candidate.memoryVersionId(),
                "approved",
                "grounded preference"
        );

        assertThat(update.previousStatus()).isEqualTo("CANDIDATE");
        assertThat(update.status()).isEqualTo("ACTIVE");
        assertThat(recordStore.findActiveByScope(SCOPE, Set.of(MemoryType.SEMANTIC), 10))
                .singleElement()
                .extracting(record -> record.memoryVersionId())
                .isEqualTo(candidate.memoryVersionId());
        assertThat(outboxStore.findAll()).hasSize(1);
    }

    private static MemoryWriteCommand command(String logicalId, MemoryStatus status) {
        return new MemoryWriteCommand(
                logicalId,
                MemoryType.SEMANTIC,
                SCOPE,
                "reviewable memory",
                "summary",
                "run-1",
                List.of("event-1"),
                List.of("run:run-1", "event:event-1"),
                List.of("USER_PREFERENCE"),
                0.8,
                0.9,
                status,
                T0,
                null,
                "MESSAGE_EVENT",
                logicalId + ":source",
                "semantic-extraction-v1"
        );
    }

    private static final class FixedClockProvider implements ObjectProvider<Clock> {
        @Override
        public Clock getObject(Object... args) {
            return CLOCK;
        }

        @Override
        public Clock getIfAvailable() {
            return CLOCK;
        }

        @Override
        public Clock getIfUnique() {
            return CLOCK;
        }

        @Override
        public Clock getObject() {
            return CLOCK;
        }
    }
}
