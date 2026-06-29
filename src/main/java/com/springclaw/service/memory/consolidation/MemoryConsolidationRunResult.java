package com.springclaw.service.memory.consolidation;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;

import java.util.List;

public record MemoryConsolidationRunResult(
        boolean created,
        MemoryRecordVersion candidate,
        List<String> sourceVersionIds
) {
    public MemoryConsolidationRunResult {
        sourceVersionIds = sourceVersionIds == null
                ? List.of()
                : sourceVersionIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    static MemoryConsolidationRunResult skipped() {
        return new MemoryConsolidationRunResult(false, null, List.of());
    }
}
