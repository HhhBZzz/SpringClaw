package com.springclaw.service.agent.lifecycle;

import java.util.Map;

public record ExecutionEvidence(String id,
                                String capabilityId,
                                String status,
                                String summary,
                                String payload,
                                Map<String, Object> metadata) {
    public ExecutionEvidence {
        id = safe(id);
        capabilityId = safe(capabilityId);
        status = safe(status);
        summary = safe(summary);
        payload = payload == null ? "" : payload;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
