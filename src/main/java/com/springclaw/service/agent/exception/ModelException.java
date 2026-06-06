package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ModelException extends AgentExecutionException {
    public ModelException(String requestId, AgentPhase phase, String errorCode, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, phase, errorCode, ErrorSeverity.FAILED, message, metadata, cause);
    }
}
