package com.springclaw.service.agent.exception;

import java.util.Map;

public class ToolResultInvalidException extends ToolExecutionException {
    public ToolResultInvalidException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, "TOOL_RESULT_INVALID", message, metadata, cause);
    }
}
