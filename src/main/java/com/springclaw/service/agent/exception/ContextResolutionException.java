package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ContextResolutionException extends RoutingException {
    public ContextResolutionException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.CONTEXT_RESOLVE, "CONTEXT_RESOLUTION_FAILED", message, metadata, cause);
    }
}
