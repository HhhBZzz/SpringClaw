package com.springclaw.service.agent;

/**
 * Lightweight verification before the final answer is returned.
 */
public record VerificationResult(String status,
                                 boolean sufficient,
                                 String summary,
                                 AgentQualityScore quality) {

    public VerificationResult(String status, boolean sufficient, String summary) {
        this(status, sufficient, summary, AgentQualityScore.baseline(sufficient));
    }

    public VerificationResult {
        status = status == null || status.isBlank() ? "success" : status;
        summary = summary == null ? "" : summary;
        quality = quality == null ? AgentQualityScore.baseline(sufficient) : quality;
    }
}
