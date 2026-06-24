package com.springclaw.runtime.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContextSnapshotRequest(
        String runId,
        String sessionKey,
        String sessionOwnerUserId,
        String channel,
        String userId,
        SessionAccessClaim sessionAccessClaim,
        String roleCode,
        String originalMessage,
        String effectiveMessage,
        String systemPrompt,
        List<String> allowedCapabilities,
        Map<String, String> providerSnapshot
) {
    public ContextSnapshotRequest {
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        sessionOwnerUserId = requireText(sessionOwnerUserId, "sessionOwnerUserId");
        channel = requireText(channel, "channel");
        userId = requireText(userId, "userId");
        sessionAccessClaim = Objects.requireNonNull(sessionAccessClaim, "sessionAccessClaim");
        roleCode = requireText(roleCode, "roleCode");
        originalMessage = originalMessage == null ? "" : originalMessage;
        effectiveMessage = effectiveMessage == null ? "" : effectiveMessage;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        allowedCapabilities = allowedCapabilities == null ? List.of() : List.copyOf(allowedCapabilities);
        providerSnapshot = providerSnapshot == null ? Map.of() : Map.copyOf(providerSnapshot);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
