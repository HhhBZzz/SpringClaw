package com.springclaw.service.agent.exception;

import java.util.Map;

public record AgentErrorPayload(String requestId,
                                String phase,
                                String errorCode,
                                String severity,
                                String message,
                                Map<String, Object> metadata) {
    public AgentErrorPayload {
        requestId = requestId == null ? "" : requestId.trim();
        phase = phase == null ? "" : phase.trim();
        errorCode = errorCode == null ? "" : errorCode.trim();
        severity = severity == null ? "" : severity.trim();
        message = message == null ? "" : message.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentErrorPayload from(AgentExecutionException ex) {
        return new AgentErrorPayload(
                ex.requestId(),
                ex.phase().name(),
                ex.errorCode(),
                ex.severity().name(),
                ex.userVisibleMessage(),
                ex.metadata()
        );
    }
}
