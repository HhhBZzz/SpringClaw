package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.SessionAccessClaim;

import java.util.Objects;

public record AcceptedRunContext(RunState runState) {

    public AcceptedRunContext {
        Objects.requireNonNull(runState, "runState");
    }

    public String runId() {
        return runState.runId();
    }

    public String sessionKey() {
        return runState.sessionKey();
    }

    public String channel() {
        return runState.channel();
    }

    public String userId() {
        return runState.userId();
    }

    public String roleCode() {
        return runState.roleCodeAtAcceptance();
    }

    public String originalMessage() {
        return runState.originalMessage();
    }

    public String responseMode() {
        return runState.responseMode();
    }

    public SessionAccessClaim sessionAccessClaim() {
        return runState.sessionAccessClaim();
    }
}
