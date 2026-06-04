package com.springclaw.service.agent;

public record AgentRunTraceEvent(String requestId,
                                 String stepName,
                                 String type,
                                 String status,
                                 String detail,
                                 long durationMs,
                                 long timestamp,
                                 Integer qualityScore,
                                 String qualityLevel,
                                 String evaluationJson) {

    public AgentRunTraceEvent(String requestId,
                              String stepName,
                              String type,
                              String status,
                              String detail,
                              long durationMs,
                              long timestamp) {
        this(requestId, stepName, type, status, detail, durationMs, timestamp, null, "", "");
    }

    public AgentRunTraceEvent {
        qualityScore = qualityScore == null ? null : Math.max(0, Math.min(100, qualityScore));
        qualityLevel = qualityLevel == null ? "" : qualityLevel;
        evaluationJson = evaluationJson == null ? "" : evaluationJson;
    }
}
