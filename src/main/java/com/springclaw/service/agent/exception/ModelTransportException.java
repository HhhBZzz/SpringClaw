package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ModelTransportException extends ModelException {
    public ModelTransportException(String requestId, AgentPhase phase, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, phase, "MODEL_TRANSPORT_FAILED", message, metadata, cause);
    }
}
