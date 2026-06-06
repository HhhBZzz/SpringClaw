package com.springclaw.service.agent.lifecycle;

import java.util.Map;

public record RunTraceEvent(String requestId,
                            AgentPhase phase,
                            String status,
                            String detail,
                            Map<String, Object> metadata,
                            long timestamp) {
    public RunTraceEvent {
        requestId = requestId == null ? "" : requestId.trim();
        phase = phase == null ? AgentPhase.INPUT_NORMALIZE : phase;
        status = status == null ? "success" : status.trim();
        detail = detail == null ? "" : detail.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        timestamp = timestamp <= 0L ? System.currentTimeMillis() : timestamp;
    }

    public static RunTraceEvent success(String requestId, AgentPhase phase, String detail) {
        return new RunTraceEvent(requestId, phase, "success", detail, Map.of(), System.currentTimeMillis());
    }
}
