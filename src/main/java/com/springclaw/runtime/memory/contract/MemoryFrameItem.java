package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryFrameItem(
        String sourceId,
        MemoryFrameSourceKind sourceKind,
        MemoryFrameLayer layer,
        String logicalMemoryId,
        String memoryVersionId,
        MemoryType memoryType,
        MemoryScopeType scopeType,
        String scopeId,
        String content,
        String contentHash,
        List<String> evidenceRefs,
        double importance,
        double confidence,
        double score,
        int version,
        Instant updatedAt
) {
    public MemoryFrameItem {
        sourceId = requireText(sourceId, "sourceId");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        layer = Objects.requireNonNull(layer, "layer");
        logicalMemoryId = optionalText(logicalMemoryId);
        memoryVersionId = optionalText(memoryVersionId);
        scopeId = optionalText(scopeId);
        content = requireText(content, "content");
        contentHash = requireText(contentHash, "contentHash");
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        requireScore(importance, "importance");
        requireScore(confidence, "confidence");
        requireScore(score, "score");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static void requireScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
