package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ModelStructuredOutputException extends ModelException {
    public ModelStructuredOutputException(String requestId, AgentPhase phase, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, phase, "MODEL_STRUCTURED_OUTPUT_FAILED", message, metadata, cause);
    }
}
