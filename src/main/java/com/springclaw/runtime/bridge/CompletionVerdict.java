package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;

import java.util.Objects;

/**
 * 一次完成判定的结论。outcome 语义：
 * COMPLETE→run.completed；DEGRADE→run.degraded（降级但产出可用）；FAIL→run.failed。
 */
public record CompletionVerdict(
        CompletionDecision.Outcome outcome,
        String reasonCode,
        String summary
) {
    public CompletionVerdict {
        Objects.requireNonNull(outcome, "outcome");
        reasonCode = reasonCode == null ? "" : reasonCode;
        summary = summary == null ? "" : summary;
    }
}
