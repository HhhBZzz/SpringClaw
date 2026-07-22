package com.springclaw.runtime.bridge;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcceptedRunContextResolverTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final String SECRET_MESSAGE = "secret message must not leak";
    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    @Test
    void resolvesIdentityFromAcceptedRunState() {
        RunStateRepository repository = mock(RunStateRepository.class);
        RunState state = createdRun();
        when(repository.requireByRunId(RUN_ID)).thenReturn(state);

        AcceptedRunContext context = new AcceptedRunContextResolver(repository).resolve(
                RUN_ID,
                matchingRequest()
        );

        assertThat(context.runState()).isSameAs(state);
        assertThat(context.runId()).isEqualTo(RUN_ID);
        assertThat(context.sessionKey()).isEqualTo("session-1");
        assertThat(context.channel()).isEqualTo("api");
        assertThat(context.userId()).isEqualTo("user-1");
        assertThat(context.roleCode()).isEqualTo("MEMBER");
        assertThat(context.originalMessage()).isEqualTo(SECRET_MESSAGE);
        assertThat(context.responseMode()).isEqualTo("agent");
    }

    @Test
    void acceptsBlankTransportDefaultsWhenAcceptedRunUsesDefaults() {
        RunStateRepository repository = mock(RunStateRepository.class);
        when(repository.requireByRunId(RUN_ID)).thenReturn(createdRun());

        AcceptedRunContext context = new AcceptedRunContextResolver(repository).resolve(
                RUN_ID,
                new ChatRequest("session-1", "user-1", SECRET_MESSAGE, "", "", null)
        );

        assertThat(context.channel()).isEqualTo("api");
        assertThat(context.responseMode()).isEqualTo("agent");
    }

    @Test
    void rejectsMismatchedRunId() {
        assertMismatch("runId", "different-run-id", matchingRequest());
    }

    @Test
    void rejectsMismatchedSessionKeyWithoutLeakingMessage() {
        assertMismatch(
                "sessionKey",
                RUN_ID,
                new ChatRequest("other-session", "user-1", SECRET_MESSAGE, "api", "agent", null)
        );
    }

    @Test
    void rejectsMismatchedNormalizedChannel() {
        assertMismatch(
                "channel",
                RUN_ID,
                new ChatRequest("session-1", "user-1", SECRET_MESSAGE, "webhook", "agent", null)
        );
    }

    @Test
    void rejectsMismatchedUserId() {
        assertMismatch(
                "userId",
                RUN_ID,
                new ChatRequest("session-1", "other-user", SECRET_MESSAGE, "api", "agent", null)
        );
    }

    @Test
    void rejectsMismatchedOriginalMessageWithoutLeakingEitherMessage() {
        assertMismatch(
                "message",
                RUN_ID,
                new ChatRequest("session-1", "user-1", "other message", "api", "agent", null)
        );
    }

    @Test
    void rejectsMismatchedNormalizedResponseMode() {
        assertMismatch(
                "responseMode",
                RUN_ID,
                new ChatRequest("session-1", "user-1", SECRET_MESSAGE, "api", "stream", null)
        );
    }

    @Test
    void rejectsTerminalRunBeforeBuildingContext() {
        RunStateRepository repository = mock(RunStateRepository.class);
        when(repository.requireByRunId(RUN_ID)).thenReturn(failedRun());

        assertThatThrownBy(() -> new AcceptedRunContextResolver(repository).resolve(
                RUN_ID,
                matchingRequest()
        ))
                .isInstanceOf(CanonicalRunContextException.class)
                .extracting(error -> ((CanonicalRunContextException) error).code())
                .isEqualTo(CanonicalRunContextException.Code.RUN_ALREADY_TERMINAL);
    }

    private static void assertMismatch(String expectedField, String suppliedRunId, ChatRequest request) {
        RunStateRepository repository = mock(RunStateRepository.class);
        when(repository.requireByRunId(suppliedRunId)).thenReturn(createdRun());

        assertThatThrownBy(() -> new AcceptedRunContextResolver(repository).resolve(
                suppliedRunId,
                request
        ))
                .isInstanceOf(CanonicalRunContextException.class)
                .satisfies(error -> {
                    CanonicalRunContextException failure =
                            (CanonicalRunContextException) error;
                    assertThat(failure.code()).isEqualTo(
                            CanonicalRunContextException.Code.ACCEPTED_REQUEST_MISMATCH
                    );
                    assertThat(failure.getMessage()).contains(expectedField);
                    assertThat(failure.getMessage()).doesNotContain(SECRET_MESSAGE);
                    assertThat(failure.getMessage()).doesNotContain("other message");
                });
    }

    private static ChatRequest matchingRequest() {
        return new ChatRequest("session-1", "user-1", SECRET_MESSAGE, "api", "agent", null);
    }

    private static RunState createdRun() {
        return state(RunStatus.CREATED, null, null);
    }

    private static RunState failedRun() {
        return state(
                RunStatus.FAILED,
                NOW.plusSeconds(1),
                new RunState.Failure("RUN_FAILED", "safe failure", false)
        );
    }

    private static RunState state(
            RunStatus status,
            Instant finishedAt,
            RunState.Failure failure
    ) {
        return new RunState(
                RUN_ID,
                RUN_ID,
                0,
                status,
                "session-1",
                "api",
                "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                ),
                "MEMBER",
                SECRET_MESSAGE,
                "agent",
                NOW,
                null,
                finishedAt == null ? NOW : finishedAt,
                finishedAt,
                NOW.plusSeconds(300),
                null,
                null,
                "",
                1,
                "",
                List.of(),
                null,
                null,
                Map.of(),
                failure,
                null
        );
    }
}
