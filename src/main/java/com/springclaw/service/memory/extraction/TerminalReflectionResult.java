package com.springclaw.service.memory.extraction;

import java.util.List;

public record TerminalReflectionResult(
        String schema,
        String outcome,
        String lesson,
        String applicability,
        String failureMode,
        List<String> evidenceRefs,
        double confidence
) {
    public TerminalReflectionResult {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
