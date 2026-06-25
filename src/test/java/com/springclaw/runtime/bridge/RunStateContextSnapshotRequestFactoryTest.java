package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshotRequest;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateContextSnapshotRequestFactoryTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void usesAcceptedRunClaimAndIdentity() {
        RunState state = created(personalClaim());

        ContextSnapshotRequest request = new RunStateContextSnapshotRequestFactory()
                .create(
                        state,
                        "effective",
                        "system",
                        List.of("web"),
                        Map.of("providerId", "provider")
                );

        assertThat(request.runId()).isEqualTo(state.runId());
        assertThat(request.sessionKey()).isEqualTo(state.sessionKey());
        assertThat(request.channel()).isEqualTo(state.channel());
        assertThat(request.userId()).isEqualTo(state.userId());
        assertThat(request.sessionAccessClaim()).isEqualTo(state.sessionAccessClaim());
        assertThat(request.roleCode()).isEqualTo(state.roleCodeAtAcceptance());
        assertThat(request.originalMessage()).isEqualTo(state.originalMessage());
        assertThat(request.effectiveMessage()).isEqualTo("effective");
        assertThat(request.allowedCapabilities()).containsExactly("web");
    }

    @Test
    void preservesSharedSessionClaim() {
        SessionAccessClaim claim = SessionAccessClaim.sharedVerified(
                "feishu",
                "group-1",
                "ou-1"
        );
        RunState state = created(claim);

        ContextSnapshotRequest request = new RunStateContextSnapshotRequestFactory()
                .create(state, "effective", "system", List.of(), Map.of());

        assertThat(request.sessionAccessClaim().claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.SHARED);
        assertThat(request.sessionAccessClaim().ownerOrSharedPrincipal())
                .isEqualTo("shared:feishu:group-1");
    }

    @Test
    void rejectsTerminalRunState() {
        RunState terminal = new RunState(
                "run-1", "run-1", 1, RunStatus.FAILED,
                "session-1", "api", "alice", personalClaim(), "USER",
                "original", "agent", T0, null, T0.plusSeconds(1),
                T0.plusSeconds(1), T0.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(),
                new RunState.Failure("FAILED", "boom", false)
        );

        assertThatThrownBy(() -> new RunStateContextSnapshotRequestFactory()
                .create(terminal, "effective", "system", List.of(), Map.of()))
                .hasMessageContaining("terminal");
    }

    private static RunState created(SessionAccessClaim claim) {
        return new RunState(
                "run-1", "run-1", 0, RunStatus.CREATED,
                claim.sessionKey(), claim.channel(), claim.acceptedUserId(),
                claim, "USER", "original", "agent", T0, null, T0,
                null, T0.plusSeconds(300), null, null, "", 1, "",
                List.of(), null, null, Map.of(), null
        );
    }

    private static SessionAccessClaim personalClaim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
    }
}
