package com.springclaw.service.memory.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class RuntimeEvaluationHistoryService {

    public static final String MEMORY_REDLINE = "MEMORY_REDLINE";
    public static final String MEMORY_PROVIDER_HARNESS = "MEMORY_PROVIDER_HARNESS";

    private final RuntimeEvaluationRunStore store;
    private final ObjectMapper objectMapper;

    public RuntimeEvaluationHistoryService(
            RuntimeEvaluationRunStore store,
            ObjectMapper objectMapper
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .findAndRegisterModules();
    }

    public RuntimeEvaluationRun recordRedline(
            MemoryEffectivenessRedlineReport report
    ) {
        Objects.requireNonNull(report, "report");
        return store.insert(new RuntimeEvaluationRun(
                null,
                MEMORY_REDLINE,
                report.schema(),
                true,
                report.total(),
                report.passed(),
                report.failed(),
                0,
                encode(report),
                report.evaluatedAt()
        ));
    }

    public RuntimeEvaluationRun recordProviderHarness(
            MemoryProviderEvaluationReport report
    ) {
        Objects.requireNonNull(report, "report");
        return store.insert(new RuntimeEvaluationRun(
                null,
                MEMORY_PROVIDER_HARNESS,
                report.schema(),
                report.enabled(),
                report.total(),
                report.passed(),
                report.failed(),
                report.skipped(),
                encode(report),
                report.evaluatedAt()
        ));
    }

    public List<RuntimeEvaluationRun> history(String evaluationType, int limit) {
        return store.listByType(normalizeType(evaluationType), safeLimit(limit));
    }

    public Optional<RuntimeEvaluationRun> latest(String evaluationType) {
        return store.latestByType(normalizeType(evaluationType));
    }

    public RuntimeEvaluationStatusSummary summary() {
        RuntimeEvaluationRun redline = store.latestByType(MEMORY_REDLINE)
                .orElse(null);
        RuntimeEvaluationRun provider = store.latestByType(MEMORY_PROVIDER_HARNESS)
                .orElse(null);
        if (redline == null) {
            return new RuntimeEvaluationStatusSummary(
                    "UNKNOWN",
                    "memory redline has not run",
                    null,
                    provider
            );
        }
        if (redline.failed() > 0) {
            return new RuntimeEvaluationStatusSummary(
                    "FAIL",
                    "memory redline has " + failureLabel(redline.failed()),
                    redline,
                    provider
            );
        }
        if (provider == null) {
            return new RuntimeEvaluationStatusSummary(
                    "DEGRADED",
                    "memory redline passed but provider harness has not run",
                    redline,
                    null
            );
        }
        if (!provider.enabled()) {
            return new RuntimeEvaluationStatusSummary(
                    "DEGRADED",
                    "memory redline passed but provider harness is disabled",
                    redline,
                    provider
            );
        }
        if (provider.failed() > 0) {
            return new RuntimeEvaluationStatusSummary(
                    "DEGRADED",
                    "memory redline passed but provider harness has "
                            + failureLabel(provider.failed()),
                    redline,
                    provider
            );
        }
        if (provider.skipped() > 0) {
            return new RuntimeEvaluationStatusSummary(
                    "DEGRADED",
                    "memory redline passed but provider harness skipped "
                            + provider.skipped() + " case"
                            + (provider.skipped() == 1 ? "" : "s"),
                    redline,
                    provider
            );
        }
        return new RuntimeEvaluationStatusSummary(
                "OK",
                "memory redline and provider harness passed",
                redline,
                provider
        );
    }

    public RuntimeEvaluationGateReport gateReport(int window) {
        int safeWindow = Math.max(2, Math.min(window, 20));
        RuntimeEvaluationStatusSummary health = summary();
        List<RuntimeEvaluationRun> redlineRecent =
                store.listByType(MEMORY_REDLINE, safeWindow);
        List<RuntimeEvaluationRun> providerRecent =
                store.listByType(MEMORY_PROVIDER_HARNESS, safeWindow);
        GateDecision gate = gateDecision(health.redlineLatest());
        TrendDecision trend = trendDecision(redlineRecent);
        return new RuntimeEvaluationGateReport(
                gate.status(),
                gate.passed(),
                gate.reason(),
                trend.trend(),
                trend.reason(),
                health,
                redlineRecent,
                providerRecent
        );
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("encode runtime evaluation result failed", ex);
        }
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private static String failureLabel(int failed) {
        return failed + " failing case" + (failed == 1 ? "" : "s");
    }

    private static GateDecision gateDecision(RuntimeEvaluationRun redline) {
        if (redline == null) {
            return new GateDecision(
                    "BLOCK",
                    false,
                    "memory redline has not run"
            );
        }
        if (redline.failed() > 0) {
            return new GateDecision(
                    "BLOCK",
                    false,
                    "latest redline has " + failureLabel(redline.failed())
            );
        }
        return new GateDecision(
                "PASS",
                true,
                "latest redline passed"
        );
    }

    private static TrendDecision trendDecision(List<RuntimeEvaluationRun> redlineRecent) {
        if (redlineRecent == null || redlineRecent.size() < 2) {
            return new TrendDecision(
                    "UNKNOWN",
                    "not enough redline history"
            );
        }
        RuntimeEvaluationRun latest = redlineRecent.get(0);
        RuntimeEvaluationRun previous = redlineRecent.get(1);
        int latestFailures = latest.failed();
        int previousFailures = previous.failed();
        if (latestFailures < previousFailures) {
            return new TrendDecision(
                    "IMPROVING",
                    "redline failures decreased from "
                            + previousFailures + " to " + latestFailures
            );
        }
        if (latestFailures > previousFailures) {
            return new TrendDecision(
                    "REGRESSING",
                    "redline failures increased from "
                            + previousFailures + " to " + latestFailures
            );
        }
        return new TrendDecision(
                "STABLE",
                "redline failures stayed at " + latestFailures
        );
    }

    private record GateDecision(String status, boolean passed, String reason) {
    }

    private record TrendDecision(String trend, String reason) {
    }

    private static String normalizeType(String evaluationType) {
        if (evaluationType == null || evaluationType.isBlank()) {
            throw new IllegalArgumentException("evaluationType must not be blank");
        }
        String normalized = evaluationType.trim().toUpperCase(Locale.ROOT);
        if (!MEMORY_REDLINE.equals(normalized)
                && !MEMORY_PROVIDER_HARNESS.equals(normalized)) {
            throw new IllegalArgumentException("unsupported evaluationType: " + evaluationType);
        }
        return normalized;
    }
}
