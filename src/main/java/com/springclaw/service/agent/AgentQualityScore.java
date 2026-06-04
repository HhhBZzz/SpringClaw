package com.springclaw.service.agent;

import java.util.List;

/**
 * Deterministic product-facing quality score for one Agent runtime run.
 */
public record AgentQualityScore(int overallScore,
                                int routeScore,
                                int toolScore,
                                int evidenceScore,
                                int reflectionScore,
                                int answerScore,
                                int costScore,
                                int riskScore,
                                String level,
                                String reason,
                                List<String> reasons) {

    public AgentQualityScore {
        overallScore = clamp(overallScore);
        routeScore = clamp(routeScore);
        toolScore = clamp(toolScore);
        evidenceScore = clamp(evidenceScore);
        reflectionScore = clamp(reflectionScore);
        answerScore = clamp(answerScore);
        costScore = clamp(costScore);
        riskScore = clamp(riskScore);
        level = safe(level, levelFor(overallScore));
        reason = safe(reason, "");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static AgentQualityScore baseline(boolean sufficient) {
        int score = sufficient ? 75 : 35;
        String reason = sufficient ? "证据反思通过，但没有生成细分评分。" : "证据反思未通过。";
        return new AgentQualityScore(score, score, score, score, score, score, 90, 90, levelFor(score), reason, List.of(reason));
    }

    public static String levelFor(int score) {
        int normalized = clamp(score);
        if (normalized >= 85) {
            return "strong";
        }
        if (normalized >= 70) {
            return "acceptable";
        }
        if (normalized >= 50) {
            return "weak";
        }
        return "failed";
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
