package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.Objects;

public record ProjectMemoryItem(
        String sourcePath,
        SourceType sourceType,
        String content,
        String contentHash,
        ReviewStatus reviewStatus,
        Instant updatedAt
) {
    public enum SourceType {
        PROJECT_BRIEF,
        CURRENT_STATE,
        ARCHITECTURE_DECISION,
        APPROVED_LEARNING,
        PROGRESS,
        USER_PREFERENCE,
        KNOWLEDGE_SOURCE,
        OTHER_REVIEWED_PROJECT_MEMORY
    }

    public enum ReviewStatus {
        APPROVED,
        ACTIVE,
        CANDIDATE,
        REJECTED
    }

    public ProjectMemoryItem {
        sourcePath = requireText(sourcePath, "sourcePath");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        content = requireText(content, "content");
        contentHash = requireText(contentHash, "contentHash");
        reviewStatus = Objects.requireNonNull(reviewStatus, "reviewStatus");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
