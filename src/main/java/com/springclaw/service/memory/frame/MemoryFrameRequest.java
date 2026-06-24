package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryScope;

import java.util.Objects;

public record MemoryFrameRequest(
        String runId,
        MemoryScope scope,
        String question
) {
    public MemoryFrameRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        runId = runId.trim();
        scope = Objects.requireNonNull(scope, "scope");
        question = question == null ? "" : question.trim();
    }
}
