package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ModelResponseExtractionException extends ModelException {
    public ModelResponseExtractionException(String requestId, AgentPhase phase, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, phase, "MODEL_RESPONSE_EXTRACTION_FAILED", message, metadata, cause);
    }
}
