package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class OparReflectionFailedException extends OparException {
    public OparReflectionFailedException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.OPAR_REFLECT, "OPAR_REFLECTION_FAILED", message, metadata, cause);
    }
}
