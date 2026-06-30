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

    @Test
    void summarizesEvaluationHealthFromLatestRedlineAndProviderRuns() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());

        assertThat(service.summary().status()).isEqualTo("UNKNOWN");

        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                5,
                0,
                0,
                Instant.parse("2026-06-30T00:02:00Z")
        ));

        RuntimeEvaluationStatusSummary degraded = service.summary();
        assertThat(degraded.status()).isEqualTo("DEGRADED");
        assertThat(degraded.summary()).contains("provider harness has not run");
        assertThat(degraded.redlineLatest()).isNotNull();
        assertThat(degraded.providerLatest()).isNull();

        store.insert(run(
                "MEMORY_PROVIDER_HARNESS",
                true,
                3,
                3,
                0,
                0,
                Instant.parse("2026-06-30T00:03:00Z")
        ));

        RuntimeEvaluationStatusSummary ok = service.summary();
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.summary()).contains("redline and provider harness passed");
        assertThat(ok.providerLatest()).isNotNull();
    }

    @Test
    void failsSummaryWhenLatestRedlineRunHasFailures() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());

        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                4,
                1,
                0,
                Instant.parse("2026-06-30T00:04:00Z")
        ));
        store.insert(run(
                "MEMORY_PROVIDER_HARNESS",
                true,
                3,
                3,
                0,
                0,
                Instant.parse("2026-06-30T00:05:00Z")
        ));

        RuntimeEvaluationStatusSummary summary = service.summary();

        assertThat(summary.status()).isEqualTo("FAIL");
        assertThat(summary.summary()).contains("redline has 1 failing case");
    }

    @Test
    void degradesSummaryWhenProviderHarnessIsDisabledOrHasFailures() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());
        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                5,
                0,
                0,
                Instant.parse("2026-06-30T00:06:00Z")
        ));

        store.insert(run(
                "MEMORY_PROVIDER_HARNESS",
                false,
                3,
                0,
                0,
                3,
                Instant.parse("2026-06-30T00:07:00Z")
        ));
        assertThat(service.summary().status()).isEqualTo("DEGRADED");
        assertThat(service.summary().summary()).contains("provider harness is disabled");

        store.insert(run(
                "MEMORY_PROVIDER_HARNESS",
                true,
                3,
                2,
                1,
                0,
                Instant.parse("2026-06-30T00:08:00Z")
        ));

        RuntimeEvaluationStatusSummary summary = service.summary();

        assertThat(summary.status()).isEqualTo("DEGRADED");
        assertThat(summary.summary()).contains("provider harness has 1 failing case");
    }

    @Test
    void blocksGateWhenRedlineHasNotRunOrLatestRedlineFails() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());

        RuntimeEvaluationGateReport missing = service.gateReport(5);

        assertThat(missing.gateStatus()).isEqualTo("BLOCK");
        assertThat(missing.gatePassed()).isFalse();
        assertThat(missing.gateReason()).contains("redline has not run");
        assertThat(missing.trend()).isEqualTo("UNKNOWN");

        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                4,
                1,
                0,
                Instant.parse("2026-06-30T00:09:00Z")
        ));

        RuntimeEvaluationGateReport failed = service.gateReport(5);

        assertThat(failed.gateStatus()).isEqualTo("BLOCK");
        assertThat(failed.gatePassed()).isFalse();
        assertThat(failed.gateReason()).contains("latest redline has 1 failing case");
        assertThat(failed.health().status()).isEqualTo("FAIL");
    }

    @Test
    void passesGateWhenLatestRedlinePassesAndReportsRedlineTrend() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());
        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                3,
                2,
                0,
                Instant.parse("2026-06-30T00:10:00Z")
        ));
        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                5,
                0,
                0,
                Instant.parse("2026-06-30T00:11:00Z")
        ));
        store.insert(run(
                "MEMORY_PROVIDER_HARNESS",
                false,
                3,
                0,
                0,
                3,
                Instant.parse("2026-06-30T00:12:00Z")
        ));

        RuntimeEvaluationGateReport report = service.gateReport(5);

        assertThat(report.gateStatus()).isEqualTo("PASS");
        assertThat(report.gatePassed()).isTrue();
        assertThat(report.gateReason()).contains("latest redline passed");
        assertThat(report.trend()).isEqualTo("IMPROVING");
        assertThat(report.trendReason()).contains("redline failures decreased from 2 to 0");
        assertThat(report.health().status()).isEqualTo("DEGRADED");
        assertThat(report.redlineRecent()).extracting(RuntimeEvaluationRun::failed)
                .containsExactly(0, 2);
    }

    @Test
    void reportsRegressingAndStableTrendsFromRecentRedlineRuns() {
        InMemoryStore store = new InMemoryStore();
        RuntimeEvaluationHistoryService service =
                new RuntimeEvaluationHistoryService(store, new ObjectMapper());
        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                5,
                0,
                0,
                Instant.parse("2026-06-30T00:13:00Z")
        ));
        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                4,
                1,
                0,
                Instant.parse("2026-06-30T00:14:00Z")
        ));

        RuntimeEvaluationGateReport regressing = service.gateReport(5);

        assertThat(regressing.trend()).isEqualTo("REGRESSING");
        assertThat(regressing.trendReason()).contains("redline failures increased from 0 to 1");

        store.insert(run(
                "MEMORY_REDLINE",
                true,
                5,
                4,
                1,
                0,
                Instant.parse("2026-06-30T00:15:00Z")
        ));

        RuntimeEvaluationGateReport stable = service.gateReport(5);

        assertThat(stable.trend()).isEqualTo("STABLE");
        assertThat(stable.trendReason()).contains("redline failures stayed at 1");
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

    private static RuntimeEvaluationRun run(
            String type,
            boolean enabled,
            int total,
            int passed,
            int failed,
            int skipped,
            Instant createdAt
    ) {
        return new RuntimeEvaluationRun(
                null,
                type,
                type.equals("MEMORY_REDLINE")
                        ? "springclaw.memory-effectiveness-redline.v1"
                        : "springclaw.memory-provider-evaluation.v1",
                enabled,
                total,
                passed,
                failed,
                skipped,
                "{\"schema\":\"test\"}",
                createdAt
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
