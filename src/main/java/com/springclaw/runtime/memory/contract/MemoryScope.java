package com.springclaw.runtime.memory.contract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.springclaw.runtime.contract.SessionAccessClaim;

import java.util.Objects;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MemoryScope {

    private final MemoryScopeType scopeType;
    private final String scopeId;
    private final String channel;
    private final String sessionKey;
    private final String requestingUserId;
    private final String authorizationPrincipal;
    private final boolean crossSessionUserMemoryAllowed;

    @JsonCreator
    private MemoryScope(
            @JsonProperty("scopeType") MemoryScopeType scopeType,
            @JsonProperty("scopeId") String scopeId,
            @JsonProperty("channel") String channel,
            @JsonProperty("sessionKey") String sessionKey,
            @JsonProperty("requestingUserId") String requestingUserId,
            @JsonProperty("authorizationPrincipal") String authorizationPrincipal,
            @JsonProperty("crossSessionUserMemoryAllowed") boolean crossSessionUserMemoryAllowed
    ) {
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
        this.scopeId = requireText(scopeId, "scopeId");
        this.channel = requireText(channel, "channel");
        this.sessionKey = requireText(sessionKey, "sessionKey");
        this.requestingUserId = requireText(
                requestingUserId,
                "requestingUserId"
        );
        this.authorizationPrincipal = requireText(
                authorizationPrincipal,
                "authorizationPrincipal"
        );
        this.crossSessionUserMemoryAllowed = crossSessionUserMemoryAllowed;
    }

    public static MemoryScope from(SessionAccessClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String channel = requireText(claim.channel(), "channel");
        String sessionKey = requireText(claim.sessionKey(), "sessionKey");
        String acceptedUserId = requireText(
                claim.acceptedUserId(),
                "acceptedUserId"
        );
        if (claim.claimType() == SessionAccessClaim.ClaimType.PERSONAL) {
            return new MemoryScope(
                    MemoryScopeType.PERSONAL_SESSION,
                    channel + ":" + sessionKey + ":" + acceptedUserId,
                    channel,
                    sessionKey,
                    acceptedUserId,
                    acceptedUserId,
                    true
            );
        }
        return new MemoryScope(
                MemoryScopeType.SHARED_SESSION,
                channel + ":" + sessionKey,
                channel,
                sessionKey,
                acceptedUserId,
                requireText(
                        claim.ownerOrSharedPrincipal(),
                        "ownerOrSharedPrincipal"
                ),
                false
        );
    }

    public static MemoryScope user(
            String channel,
            String sessionKey,
            String userId
    ) {
        String normalizedUserId = requireText(userId, "userId");
        return new MemoryScope(
                MemoryScopeType.USER,
                normalizedUserId,
                requireText(channel, "channel"),
                requireText(sessionKey, "sessionKey"),
                normalizedUserId,
                normalizedUserId,
                true
        );
    }

    public MemoryScopeType scopeType() {
        return scopeType;
    }

    public String scopeId() {
        return scopeId;
    }

    public String channel() {
        return channel;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public String requestingUserId() {
        return requestingUserId;
    }

    public String authorizationPrincipal() {
        return authorizationPrincipal;
    }

    public boolean crossSessionUserMemoryAllowed() {
        return crossSessionUserMemoryAllowed;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemoryScope that)) {
            return false;
        }
        return crossSessionUserMemoryAllowed
                == that.crossSessionUserMemoryAllowed
                && scopeType == that.scopeType
                && scopeId.equals(that.scopeId)
                && channel.equals(that.channel)
                && sessionKey.equals(that.sessionKey)
                && requestingUserId.equals(that.requestingUserId)
                && authorizationPrincipal.equals(that.authorizationPrincipal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                scopeType,
                scopeId,
                channel,
                sessionKey,
                requestingUserId,
                authorizationPrincipal,
                crossSessionUserMemoryAllowed
        );
    }

    @Override
    public String toString() {
        return "MemoryScope[scopeType=" + scopeType
                + ", scopeId=" + scopeId
                + ", channel=" + channel
                + ", sessionKey=" + sessionKey
                + ", requestingUserId=" + requestingUserId
                + ", authorizationPrincipal=" + authorizationPrincipal
                + ", crossSessionUserMemoryAllowed="
                + crossSessionUserMemoryAllowed + "]";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
