package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.SessionAccessClaim;

import java.time.Instant;
import java.util.Objects;

public record RunAcceptance(
        String runId,
        String sessionKey,
        String channel,
        String userId,
        SessionAccessClaim sessionAccessClaim,
        String roleCodeAtAcceptance,
        String originalMessage,
        String responseMode,
        Instant acceptedAt,
        Instant deadlineAt
) {
    public RunAcceptance {
        runId = requireText(runId, "runId");
        sessionKey = requireIdentityText(sessionKey, "sessionKey");
        channel = requireIdentityText(channel, "channel");
        userId = requireIdentityText(userId, "userId");
        sessionAccessClaim = Objects.requireNonNull(
                sessionAccessClaim,
                "sessionAccessClaim"
        );
        requireClaimField(
                channel,
                sessionAccessClaim.channel(),
                "channel"
        );
        requireClaimField(
                sessionKey,
                sessionAccessClaim.sessionKey(),
                "sessionKey"
        );
        requireClaimField(
                userId,
                sessionAccessClaim.acceptedUserId(),
                "acceptedUserId/userId"
        );
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

    private static String requireIdentityText(String value, String field) {
        return requireText(value, field).trim();
    }

    private static void requireClaimField(
            String acceptanceValue,
            String claimValue,
            String field
    ) {
        if (!acceptanceValue.equals(claimValue)) {
            throw new IllegalArgumentException(
                    "sessionAccessClaim " + field + " must match RunAcceptance"
            );
        }
    }
}
