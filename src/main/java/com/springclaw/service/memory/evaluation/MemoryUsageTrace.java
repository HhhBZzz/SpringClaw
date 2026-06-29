package com.springclaw.service.memory.evaluation;

import java.util.List;

public record MemoryUsageTrace(
        boolean memoryInjected,
        boolean memoryReferencedInAnswer,
        ReferenceKind memoryReferenceKind,
        String memoryUseJudgedBy,
        List<String> referencedSourceIds
) {
    public enum ReferenceKind {
        EXPLICIT,
        PARAPHRASE,
        NONE
    }

    public MemoryUsageTrace {
        memoryReferenceKind = memoryReferenceKind == null
                ? ReferenceKind.NONE
                : memoryReferenceKind;
        memoryUseJudgedBy = memoryUseJudgedBy == null || memoryUseJudgedBy.isBlank()
                ? "deterministic"
                : memoryUseJudgedBy.trim();
        referencedSourceIds = referencedSourceIds == null
                ? List.of()
                : referencedSourceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (!memoryReferencedInAnswer && memoryReferenceKind != ReferenceKind.NONE) {
            throw new IllegalArgumentException(
                    "memoryReferenceKind must be NONE when memoryReferencedInAnswer=false"
            );
        }
    }

    public static MemoryUsageTrace none(boolean memoryInjected) {
        return new MemoryUsageTrace(
                memoryInjected,
                false,
                ReferenceKind.NONE,
                "deterministic",
                List.of()
        );
    }
}
