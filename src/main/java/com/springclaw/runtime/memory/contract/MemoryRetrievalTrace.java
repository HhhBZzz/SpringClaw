package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MemoryRetrievalTrace(
        String runId,
        MemoryScope scope,
        String frameHash,
        Map<String, Integer> sourceCounts,
        Map<String, Integer> includedCounts,
        Map<MemoryFrameOmission.Category, Integer> omissionCounts,
        List<String> sourceWarnings,
        Instant capturedAt
) {
    public MemoryRetrievalTrace {
        runId = requireText(runId, "runId");
        scope = Objects.requireNonNull(scope, "scope");
        frameHash = requireText(frameHash, "frameHash");
        sourceCounts = sourceCounts == null ? Map.of() : Map.copyOf(sourceCounts);
        includedCounts = includedCounts == null ? Map.of() : Map.copyOf(includedCounts);
        omissionCounts = omissionCounts == null ? Map.of() : Map.copyOf(omissionCounts);
        sourceWarnings = sourceWarnings == null ? List.of() : List.copyOf(sourceWarnings);
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
