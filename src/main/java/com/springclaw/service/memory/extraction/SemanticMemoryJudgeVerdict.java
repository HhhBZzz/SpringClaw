package com.springclaw.service.memory.extraction;

public record SemanticMemoryJudgeVerdict(
        String schema,
        String verdict,
        double confidence,
        boolean evidenceGrounded,
        boolean hypothetical,
        boolean sensitive,
        String reason
) {
}
