package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ParameterException extends AgentExecutionException {
    public ParameterException(String requestId, AgentPhase phase, String errorCode, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, phase, errorCode, ErrorSeverity.RECOVERABLE, message, metadata, cause);
    }
}
