package com.springclaw.runtime.contract;

import java.util.Objects;

public record SessionAccessClaim(
        ClaimType claimType,
        AcceptanceOrigin acceptanceOrigin,
        String channel,
        String sessionKey,
        String ownerOrSharedPrincipal,
        String acceptedUserId
) {
    public enum ClaimType {
        PERSONAL,
        SHARED
    }

    public enum AcceptanceOrigin {
        AUTHENTICATED_API,
        VERIFIED_WEBHOOK,
        SCHEDULED_TASK
    }

    public SessionAccessClaim {
        claimType = Objects.requireNonNull(claimType, "claimType");
        acceptanceOrigin = Objects.requireNonNull(
                acceptanceOrigin,
                "acceptanceOrigin"
        );
        channel = requireText(channel, "channel");
        sessionKey = requireText(sessionKey, "sessionKey");
        ownerOrSharedPrincipal = requireText(
                ownerOrSharedPrincipal,
                "ownerOrSharedPrincipal"
        );
        acceptedUserId = requireText(acceptedUserId, "acceptedUserId");
        if (claimType == ClaimType.SHARED
                && acceptanceOrigin != AcceptanceOrigin.VERIFIED_WEBHOOK) {
            throw new IllegalArgumentException(
                    "SHARED claim requires VERIFIED_WEBHOOK origin"
            );
        }
        if (claimType == ClaimType.PERSONAL
                && !ownerOrSharedPrincipal.equals(acceptedUserId)) {
            throw new IllegalArgumentException(
                    "PERSONAL principal must equal acceptedUserId"
            );
        }
    }

    public static SessionAccessClaim personal(
            AcceptanceOrigin origin,
            String channel,
            String sessionKey,
            String userId
    ) {
        return new SessionAccessClaim(
                ClaimType.PERSONAL,
                origin,
                channel,
                sessionKey,
                userId,
                userId
        );
    }

    public static SessionAccessClaim sharedVerified(
            String channel,
            String sessionKey,
            String acceptedUserId
    ) {
        return new SessionAccessClaim(
                ClaimType.SHARED,
                AcceptanceOrigin.VERIFIED_WEBHOOK,
                channel,
                sessionKey,
                "shared:" + channel + ":" + sessionKey,
                acceptedUserId
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
