package com.springclaw.runtime.bridge;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;

public final class AcceptedRunContextResolver {

    private final RunStateRepository runStateRepository;

    public AcceptedRunContextResolver(RunStateRepository runStateRepository) {
        this.runStateRepository = Objects.requireNonNull(
                runStateRepository,
                "runStateRepository"
        );
    }

    public AcceptedRunContext resolve(String runId, ChatRequest request) {
        if (!StringUtils.hasText(runId)) {
            throw mismatch("runId");
        }
        Objects.requireNonNull(request, "request");

        RunState runState = runStateRepository.requireByRunId(runId);
        requireMatching("runId", runId, runState.runId());
        requireMatching("requestId", runId, runState.requestId());
        if (runState.status().isTerminal()) {
            throw new CanonicalRunContextException(
                    CanonicalRunContextException.Code.RUN_ALREADY_TERMINAL,
                    "cannot build context for terminal run"
            );
        }
        requireMatching("sessionKey", request.sessionKey(), runState.sessionKey());
        requireMatching("channel", normalizeChannel(request.channel()), runState.channel());
        requireMatching("userId", request.userId(), runState.userId());
        requireMatching("message", request.message(), runState.originalMessage());
        requireMatching(
                "responseMode",
                normalizeResponseMode(request.responseMode()),
                runState.responseMode()
        );
        requireMatchingClaim(runState);
        return new AcceptedRunContext(runState);
    }

    private static void requireMatchingClaim(RunState runState) {
        SessionAccessClaim claim = runState.sessionAccessClaim();
        requireMatching("sessionAccessClaim.channel", claim.channel(), runState.channel());
        requireMatching("sessionAccessClaim.sessionKey", claim.sessionKey(), runState.sessionKey());
        requireMatching(
                "sessionAccessClaim.acceptedUserId",
                claim.acceptedUserId(),
                runState.userId()
        );
    }

    private static void requireMatching(String field, String actual, String expected) {
        if (!Objects.equals(actual, expected)) {
            throw mismatch(field);
        }
    }

    private static CanonicalRunContextException mismatch(String field) {
        return new CanonicalRunContextException(
                CanonicalRunContextException.Code.ACCEPTED_REQUEST_MISMATCH,
                "accepted request mismatch: " + field
        );
    }

    private static String normalizeChannel(String value) {
        return StringUtils.hasText(value)
                ? value.trim().toLowerCase(Locale.ROOT)
                : "api";
    }

    private static String normalizeResponseMode(String value) {
        return StringUtils.hasText(value)
                ? value.trim().toLowerCase(Locale.ROOT)
                : "agent";
    }
}
