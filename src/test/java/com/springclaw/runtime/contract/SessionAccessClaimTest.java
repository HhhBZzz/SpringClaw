package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionAccessClaimTest {

    @Test
    void normalizesNonBlankTextFields() {
        SessionAccessClaim claim = new SessionAccessClaim(
                SessionAccessClaim.ClaimType.SHARED,
                SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK,
                " feishu ",
                " feishu:group:g1 ",
                " shared:feishu:feishu:group:g1 ",
                " alice "
        );

        assertThat(claim.channel()).isEqualTo("feishu");
        assertThat(claim.sessionKey()).isEqualTo("feishu:group:g1");
        assertThat(claim.ownerOrSharedPrincipal())
                .isEqualTo("shared:feishu:feishu:group:g1");
        assertThat(claim.acceptedUserId()).isEqualTo("alice");
    }

    @Test
    void sharedClaimRequiresVerifiedWebhookOrigin() {
        assertThatThrownBy(() -> new SessionAccessClaim(
                SessionAccessClaim.ClaimType.SHARED,
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "feishu",
                "feishu:group:g1",
                "shared:feishu:feishu:group:g1",
                "alice"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERIFIED_WEBHOOK");
    }

    @Test
    void personalClaimUsesAcceptedUserAsPrincipal() {
        SessionAccessClaim claim = SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "feishu",
                "feishu:group:g1",
                "alice"
        );

        assertThat(claim.claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.PERSONAL);
        assertThat(claim.ownerOrSharedPrincipal()).isEqualTo("alice");
    }

    @Test
    void personalClaimRejectsDifferentPrincipal() {
        assertThatThrownBy(() -> new SessionAccessClaim(
                SessionAccessClaim.ClaimType.PERSONAL,
                SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK,
                "feishu",
                "feishu:p2p:c1",
                "bob",
                "alice"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedUserId");
    }

    @Test
    void sharedFactoryDerivesStableSharedPrincipal() {
        SessionAccessClaim claim = SessionAccessClaim.sharedVerified(
                "feishu",
                "feishu:group:g1",
                "alice"
        );

        assertThat(claim.claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.SHARED);
        assertThat(claim.acceptanceOrigin())
                .isEqualTo(SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK);
        assertThat(claim.ownerOrSharedPrincipal())
                .isEqualTo("shared:feishu:feishu:group:g1");
    }
}
