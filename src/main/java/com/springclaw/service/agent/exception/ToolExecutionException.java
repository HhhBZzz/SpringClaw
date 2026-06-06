package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class ToolExecutionException extends AgentExecutionException {
    public ToolExecutionException(String requestId, String errorCode, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.TOOL_EXECUTE, errorCode, ErrorSeverity.FAILED, message, metadata, cause);
    }
}
