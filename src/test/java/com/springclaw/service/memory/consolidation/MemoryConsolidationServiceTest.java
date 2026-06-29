package com.springclaw.service.memory.consolidation;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryVersionFactory;
import com.springclaw.service.memory.MemoryWriteCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConsolidationServiceTest {

    private static final Instant T0 = Instant.parse("2026-06-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void consolidatesRepeatedPreferenceEpisodesIntoSemanticCandidateOnce() {
        Fixture fixture = new Fixture();
        MemoryScope scope = MemoryScope.user("api", "session-old", "alice");
        MemoryRecordVersion firstEpisode = fixture.createEpisode(scope, "episode-1",
                "User asked for concise Chinese status updates.");
        MemoryRecordVersion secondEpisode = fixture.createEpisode(scope, "episode-2",
                "User again requested short Chinese progress summaries.");
        MemoryRecordVersion thirdEpisode = fixture.createEpisode(scope, "episode-3",
                "User rejected long English progress reports.");

        MemoryConsolidationRunResult first = fixture.service.consolidate(scope, 20);
        MemoryConsolidationRunResult second = fixture.service.consolidate(scope, 20);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.candidate().memoryVersionId())
                .isEqualTo(first.candidate().memoryVersionId());

        List<MemoryRecordVersion> candidates = fixture.recordStore.findByStatus(
                MemoryStatus.CANDIDATE,
                10
        );
        assertThat(candidates).hasSize(1);
        MemoryRecordVersion candidate = candidates.get(0);
        assertThat(candidate.memoryType()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(candidate.content()).contains("short Chinese progress summaries");
        assertThat(candidate.evidenceRefs())
                .containsExactlyInAnyOrder(
                        "evidence-episode-1",
                        "evidence-episode-2",
                        "evidence-episode-3"
                );
        assertThat(first.sourceVersionIds())
                .containsExactlyInAnyOrder(
                        firstEpisode.memoryVersionId(),
                        secondEpisode.memoryVersionId(),
                        thirdEpisode.memoryVersionId()
                );
    }

    @Test
    void skipsScopeWithoutEnoughRelatedEpisodes() {
        Fixture fixture = new Fixture();
        MemoryScope scope = MemoryScope.user("api", "session-old", "alice");
        fixture.createEpisode(scope, "episode-1", "User asked for concise updates.");

        MemoryConsolidationRunResult result = fixture.service.consolidate(scope, 20);

        assertThat(result.created()).isFalse();
        assertThat(result.candidate()).isNull();
        assertThat(fixture.recordStore.findByStatus(MemoryStatus.CANDIDATE, 10))
                .isEmpty();
    }

    private static final class Fixture {
        private final InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
        private final MemoryManagementService memoryManagementService = new MemoryManagementService(
                recordStore,
                new InMemoryMemoryIndexOutboxStore(),
                new MemoryVersionFactory(CLOCK)
        );
        private final MemoryConsolidationService service = new MemoryConsolidationService(
                recordStore,
                memoryManagementService,
                new MemoryConsolidationProposer(CLOCK)
        );

        private MemoryRecordVersion createEpisode(MemoryScope scope, String versionId, String content) {
            return memoryManagementService.create(new MemoryWriteCommand(
                    "logical-" + versionId,
                    MemoryType.EPISODIC,
                    scope,
                    content,
                    content,
                    "run-" + versionId,
                    List.of("event-" + versionId),
                    List.of("evidence-" + versionId),
                    List.of("preference", "summary"),
                    0.75,
                    0.85,
                    MemoryStatus.ACTIVE,
                    T0,
                    null,
                    "REFLECTION",
                    "run-" + versionId,
                    "terminal-reflection-v1"
            ));
        }
    }
}
