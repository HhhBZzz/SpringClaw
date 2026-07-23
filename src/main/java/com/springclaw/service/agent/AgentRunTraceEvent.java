package com.springclaw.service.agent;

import com.springclaw.runtime.contract.AgentParadigm;

public record AgentRunTraceEvent(String requestId,
                                 String stepName,
                                 String type,
                                 String status,
                                 String detail,
                                 long durationMs,
                                 long timestamp,
                                 Integer qualityScore,
                                 String qualityLevel,
                                 String evaluationJson,
                                 String stepSchema,
                                 String category,
                                 String action,
                                 String target,
                                 String source,
                                 String riskLevel,
                                 AgentParadigm paradigm) {

    public static final String TIMELINE_STEP_SCHEMA = "springclaw.timeline-step.v1";

    public AgentRunTraceEvent(String requestId,
                              String stepName,
                              String type,
                              String status,
                              String detail,
                              long durationMs,
                              long timestamp) {
        this(requestId, stepName, type, status, detail, durationMs, timestamp, null, "", "");
    }

    public AgentRunTraceEvent(String requestId,
                              String stepName,
                              String type,
                              String status,
                              String detail,
                              long durationMs,
                              long timestamp,
                              Integer qualityScore,
                              String qualityLevel,
                              String evaluationJson) {
        this(requestId,
                stepName,
                type,
                status,
                detail,
                durationMs,
                timestamp,
                qualityScore,
                qualityLevel,
                evaluationJson,
                TIMELINE_STEP_SCHEMA,
                defaultText(type, "agent"),
                defaultText(type, "agent"),
                defaultText(stepName, type),
                "",
                defaultRisk(type),
                null);
    }

    public AgentRunTraceEvent {
        qualityScore = qualityScore == null ? null : Math.max(0, Math.min(100, qualityScore));
        qualityLevel = qualityLevel == null ? "" : qualityLevel;
        evaluationJson = evaluationJson == null ? "" : evaluationJson;
        stepSchema = defaultText(stepSchema, TIMELINE_STEP_SCHEMA);
        category = defaultText(category, type);
        action = defaultText(action, category);
        target = defaultText(target, stepName);
        source = source == null ? "" : source;
        riskLevel = defaultText(riskLevel, defaultRisk(type));
    }

    private static String defaultRisk(String type) {
        return "tool".equalsIgnoreCase(type) || "skill".equalsIgnoreCase(type) ? "read" : "";
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }
}
