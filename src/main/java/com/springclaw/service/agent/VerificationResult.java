package com.springclaw.service.agent;

/**
 * Lightweight verification before the final answer is returned.
 */
public record VerificationResult(String status,
                                 boolean sufficient,
                                 String summary) {

    public VerificationResult {
        status = status == null || status.isBlank() ? "success" : status;
        summary = summary == null ? "" : summary;
    }
}
