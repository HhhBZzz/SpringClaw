package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class OparActionFailedException extends OparException {
    public OparActionFailedException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.OPAR_ACT, "OPAR_ACTION_FAILED", message, metadata, cause);
    }
}
