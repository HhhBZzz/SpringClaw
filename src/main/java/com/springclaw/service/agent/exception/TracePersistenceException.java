package com.springclaw.service.agent.exception;

import java.util.Map;

public class TracePersistenceException extends MemoryBoundaryException {
    public TracePersistenceException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, "TRACE_PERSISTENCE_FAILED", message, metadata, cause);
    }
}
