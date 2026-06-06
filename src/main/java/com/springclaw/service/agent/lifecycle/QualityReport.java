package com.springclaw.service.agent.lifecycle;

import java.util.List;

public record QualityReport(int overallScore, String level, List<String> reasons) {
    public QualityReport {
        overallScore = Math.max(0, Math.min(100, overallScore));
        level = level == null ? "" : level.trim();
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static QualityReport empty() {
        return new QualityReport(0, "unknown", List.of());
    }
}
