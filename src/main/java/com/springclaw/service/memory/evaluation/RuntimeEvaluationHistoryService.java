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
