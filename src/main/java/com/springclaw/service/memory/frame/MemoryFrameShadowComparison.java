package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrameOmission;

import java.util.Map;

public record MemoryFrameShadowComparison(
        String runId,
        String legacyObservePromptHash,
        String frameHash,
        int legacyMemoryLearningActiveCount,
        int legacyMemoryLearningFilteredCount,
        Map<String, Integer> frameLayerCounts,
        Map<MemoryFrameOmission.Category, Integer> omissionCounts
) {
    public MemoryFrameShadowComparison {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        runId = runId.trim();
        legacyObservePromptHash = requireText(
                legacyObservePromptHash,
                "legacyObservePromptHash"
        );
        frameHash = requireText(frameHash, "frameHash");
        legacyMemoryLearningActiveCount = Math.max(0, legacyMemoryLearningActiveCount);
        legacyMemoryLearningFilteredCount = Math.max(0, legacyMemoryLearningFilteredCount);
        frameLayerCounts = frameLayerCounts == null ? Map.of() : Map.copyOf(frameLayerCounts);
        omissionCounts = omissionCounts == null ? Map.of() : Map.copyOf(omissionCounts);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
