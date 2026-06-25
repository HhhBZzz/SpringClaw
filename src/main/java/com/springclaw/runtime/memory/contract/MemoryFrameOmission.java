package com.springclaw.runtime.memory.contract;

import java.util.Objects;

public record MemoryFrameOmission(
        Category category,
        MemoryFrameLayer layer,
        String sourceId,
        String reason
) {
    public enum Category {
        BUDGET_TRUNCATED,
        AUTHORIZATION_SCOPE_MISMATCH,
        CONFLICT,
        EXPIRED,
        DUPLICATE_CONTENT,
        LOW_SCORE,
        UNSUPPORTED_TYPE,
        STALE_VECTOR_HIT,
        VECTOR_UNAVAILABLE,
        SOURCE_UNAVAILABLE
    }

    public MemoryFrameOmission {
        category = Objects.requireNonNull(category, "category");
        layer = Objects.requireNonNull(layer, "layer");
        sourceId = optionalText(sourceId);
        reason = optionalText(reason);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
