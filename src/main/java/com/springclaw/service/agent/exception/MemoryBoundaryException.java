package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class MemoryBoundaryException extends AgentExecutionException {
    public MemoryBoundaryException(String requestId, String errorCode, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.MEMORY_SNAPSHOT, errorCode, ErrorSeverity.RECOVERABLE, message, metadata, cause);
    }
}
