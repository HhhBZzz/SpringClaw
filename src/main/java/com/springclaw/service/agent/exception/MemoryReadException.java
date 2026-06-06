package com.springclaw.service.agent.exception;

import java.util.Map;

public class MemoryReadException extends MemoryBoundaryException {
    public MemoryReadException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, "MEMORY_READ_FAILED", message, metadata, cause);
    }
}
