package com.springclaw.service.agent.exception;

import java.util.Map;

public class ToolUnavailableException extends ToolExecutionException {
    public ToolUnavailableException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, "TOOL_UNAVAILABLE", message, metadata, cause);
    }
}
