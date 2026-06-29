package com.springclaw.service.memory.evaluation;

import java.time.LocalDateTime;
import java.util.List;

public record MemoryUsageTraceView(
        String requestId,
        boolean memoryInjected,
        boolean memoryReferencedInAnswer,
        String memoryReferenceKind,
        String memoryUseJudgedBy,
        List<String> referencedSourceIds,
        String sourceEventKey,
        LocalDateTime observedAt
) {
    public MemoryUsageTraceView {
        requestId = text(requestId);
        memoryReferenceKind = text(memoryReferenceKind);
        memoryUseJudgedBy = text(memoryUseJudgedBy);
        referencedSourceIds = referencedSourceIds == null
                ? List.of()
                : referencedSourceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        sourceEventKey = text(sourceEventKey);
    }

    public static MemoryUsageTraceView empty(String requestId) {
        return new MemoryUsageTraceView(
                requestId,
                false,
                false,
                "NONE",
                "unavailable",
                List.of(),
                "",
                null
        );
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
