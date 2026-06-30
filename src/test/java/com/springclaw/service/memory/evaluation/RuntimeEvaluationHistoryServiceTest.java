package com.springclaw.service.memory.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEvaluationHistoryServiceTest {

    @Test
    void recordsRedlineAndProviderHarnessRunsForHistoryQueries() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());

        RuntimeEvaluationRun redline = service.recordRedline(redlineReport());
        RuntimeEvaluationRun provider = service.recordProviderHarness(providerReport());

        assertThat(redline.evaluationType()).isEqualTo("MEMORY_REDLINE");
        assertThat(redline.schemaVersion()).isEqualTo("springclaw.memory-effectiveness-redline.v1");
        assertThat(redline.enabled()).isTrue();
        assertThat(redline.total()).isEqualTo(1);
        assertThat(redline.passed()).isEqualTo(1);
        assertThat(redline.failed()).isZero();
        assertThat(redline.skipped()).isZero();
        assertThat(redline.resultJson()).contains("cross_session_preference_recall");

        assertThat(provider.evaluationType()).isEqualTo("MEMORY_PROVIDER_HARNESS");
        assertThat(provider.schemaVersion()).isEqualTo("springclaw.memory-provider-evaluation.v1");
        assertThat(provider.enabled()).isFalse();
        assertThat(provider.skipped()).isEqualTo(1);
        assertThat(provider.resultJson()).contains("provider_semantic_extraction_json");

        assertThat(service.history("MEMORY_REDLINE", 20))
                .extracting(RuntimeEvaluationRun::id)
                .containsExactly(redline.id());
        assertThat(service.latest("MEMORY_PROVIDER_HARNESS"))
                .map(RuntimeEvaluationRun::id)
                .contains(provider.id());
    }

    @Test
    void capsHistoryLimitToProtectRuntimeConsoleQueries() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());

        assertThat(service.history("MEMORY_REDLINE", -5)).isEmpty();

        assertThat(store.lastLimit).isEqualTo(1);
    }

    private static MemoryEffectivenessRedlineReport redlineReport() {
        return new MemoryEffectivenessRedlineReport(
                "springclaw.memory-effectiveness-redline.v1",
                1,
                1,
                0,
                List.of(new MemoryEffectivenessRedlineReportCase(
                        "cross_session_preference_recall",
                        "Cross-session preference recall",
                        true,
                        "recalled preference",
                        List.of("event:chat:run-1:user")
                )),
                Instant.parse("2026-06-30T00:00:00Z")
        );
    }

    private static MemoryProviderEvaluationReport providerReport() {
        return new MemoryProviderEvaluationReport(
                "springclaw.memory-provider-evaluation.v1",
                false,
                1,
                0,
                0,
                1,
                List.of(new MemoryProviderEvaluationCase(
                        "provider_semantic_extraction_json",
                        "Provider semantic extraction JSON",
                        "SKIPPED",
                        "disabled",
                        List.of("springclaw.memory.evaluation.provider-harness-enabled=false")
                )),
                Instant.parse("2026-06-30T00:01:00Z")
        );
    }

    private static final class InMemoryStore implements RuntimeEvaluationRunStore {
        private final List<RuntimeEvaluationRun> rows = new ArrayList<>();
        private long nextId = 1;
        private int lastLimit;

        @Override
        public RuntimeEvaluationRun insert(RuntimeEvaluationRun run) {
            RuntimeEvaluationRun persisted = new RuntimeEvaluationRun(
                    nextId++,
                    run.evaluationType(),
                    run.schemaVersion(),
                    run.enabled(),
                    run.total(),
                    run.passed(),
                    run.failed(),
                    run.skipped(),
                    run.resultJson(),
                    run.createdAt()
            );
            rows.add(persisted);
            return persisted;
        }

        @Override
        public List<RuntimeEvaluationRun> listByType(String evaluationType, int limit) {
            lastLimit = limit;
            return rows.stream()
                    .filter(row -> row.evaluationType().equals(evaluationType))
                    .sorted(Comparator.comparing(RuntimeEvaluationRun::id).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<RuntimeEvaluationRun> latestByType(String evaluationType) {
            return listByType(evaluationType, 1).stream().findFirst();
        }
    }
}
