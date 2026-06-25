package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.Objects;

public record ShortTermMemoryEntry(
        long eventId,
        String eventKey,
        String requestId,
        String role,
        String userId,
        String content,
        Instant occurredAt
) {
    public ShortTermMemoryEntry {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        eventKey = requireText(eventKey, "eventKey");
        requestId = requireText(requestId, "requestId");
        role = requireText(role, "role");
        userId = requireText(userId, "userId");
        content = requireText(content, "content");
        if (content.length() > 4_000) {
            throw new IllegalArgumentException(
                    "content must not exceed 4000 characters"
            );
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
