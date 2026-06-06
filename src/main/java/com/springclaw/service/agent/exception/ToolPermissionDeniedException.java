package com.springclaw.service.agent.exception;

import java.util.Map;

public class ToolPermissionDeniedException extends ToolExecutionException {
    public ToolPermissionDeniedException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, "TOOL_PERMISSION_DENIED", message, metadata, cause);
    }
}
