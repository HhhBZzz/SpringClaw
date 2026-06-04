package com.springclaw.service.agent;

public record AgentRunTraceEvent(String requestId,
                                 String stepName,
                                 String type,
                                 String status,
                                 String detail,
                                 long durationMs,
                                 long timestamp) {
}
