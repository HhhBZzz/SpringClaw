package com.springclaw.service.memory.extraction;

import java.util.List;

public record SemanticMemoryExtractionResult(
        String schema,
        List<SemanticMemoryCandidate> candidates
) {
    public SemanticMemoryExtractionResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
