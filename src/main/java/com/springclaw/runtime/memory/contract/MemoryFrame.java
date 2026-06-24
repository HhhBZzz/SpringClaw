package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MemoryFrame(
        String runId,
        MemoryScope scope,
        List<MemoryFrameItem> workingMemoryRefs,
        List<MemoryFrameItem> shortTermTurns,
        List<MemoryFrameItem> episodicItems,
        List<MemoryFrameItem> semanticFacts,
        List<MemoryFrameItem> proceduralRules,
        List<MemoryFrameItem> projectItems,
        Map<String, String> sourceSummary,
        List<MemoryFrameOmission> omissions,
        Instant capturedAt,
        String frameHash
) {
    public MemoryFrame {
        runId = requireText(runId, "runId");
        scope = Objects.requireNonNull(scope, "scope");
        workingMemoryRefs = copyItems(workingMemoryRefs);
        shortTermTurns = copyItems(shortTermTurns);
        episodicItems = copyItems(episodicItems);
        semanticFacts = copyItems(semanticFacts);
        proceduralRules = copyItems(proceduralRules);
        projectItems = copyItems(projectItems);
        sourceSummary = sourceSummary == null ? Map.of() : Map.copyOf(sourceSummary);
        omissions = omissions == null ? List.of() : List.copyOf(omissions);
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        frameHash = requireText(frameHash, "frameHash");
    }

    private static List<MemoryFrameItem> copyItems(List<MemoryFrameItem> items) {
        return items == null ? List.of() : List.copyOf(items);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
