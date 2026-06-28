package com.springclaw.service.memory.extraction;

import java.util.List;

public record SemanticMemoryCandidate(
        String kind,
        String content,
        String subject,
        String scopeType,
        double importance,
        double confidence,
        List<String> sourceEventKeys,
        String sourceRunId,
        String reason,
        boolean hypothetical
) {
    public SemanticMemoryCandidate {
        sourceEventKeys = sourceEventKeys == null ? List.of() : List.copyOf(sourceEventKeys);
    }
}
