package com.springclaw.service.agent.exception;

import java.util.Map;

public class MemoryWriteException extends MemoryBoundaryException {
    public MemoryWriteException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, "MEMORY_WRITE_FAILED", message, metadata, cause);
    }
}
