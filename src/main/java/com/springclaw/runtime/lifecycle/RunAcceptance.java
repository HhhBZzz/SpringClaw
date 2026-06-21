package com.springclaw.runtime.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record RunAcceptance(
        String runId,
        String sessionKey,
        String channel,
        String userId,
        String roleCodeAtAcceptance,
        String originalMessage,
        String responseMode,
        Instant acceptedAt,
        Instant deadlineAt
) {
    public RunAcceptance {
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        channel = requireText(channel, "channel");
        userId = requireText(userId, "userId");
        roleCodeAtAcceptance = requireText(roleCodeAtAcceptance, "roleCodeAtAcceptance");
        originalMessage = Objects.requireNonNullElse(originalMessage, "");
        responseMode = requireText(responseMode, "responseMode");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (deadlineAt.isBefore(acceptedAt)) {
            throw new IllegalArgumentException("deadlineAt must not be before acceptedAt");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
