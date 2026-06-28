package com.springclaw.service.memory.extraction;

import java.time.Clock;
import java.util.Objects;

public record MemoryExtractionPolicy(
        int maxSourceEvents,
        double autoActiveImportanceThreshold,
        double autoActiveConfidenceThreshold,
        Clock clock
) {
    public MemoryExtractionPolicy {
        maxSourceEvents = Math.max(1, Math.min(maxSourceEvents, 100));
        requireScore(autoActiveImportanceThreshold, "autoActiveImportanceThreshold");
        requireScore(autoActiveConfidenceThreshold, "autoActiveConfidenceThreshold");
        clock = Objects.requireNonNull(clock, "clock");
    }

    private static void requireScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
