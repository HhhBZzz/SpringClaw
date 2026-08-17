package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatExecutionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedModelVerifierTest {

    private final TrustedModelVerifier verifier = new TrustedModelVerifier();

    @Test
    void modelEnabledWithAnswerIsComplete() {
        CompletionVerdict verdict = verifier.verify(null,
                new ChatExecutionResult("o", "p", "a", "r", true), "最终回答");
        assertThat(verdict.outcome())
                .isEqualTo(com.springclaw.runtime.contract.CompletionDecision.Outcome.COMPLETE);
        assertThat(verdict.reasonCode()).isEqualTo("MODEL_VERIFIED_ANSWER");
    }

    @Test
    void localFallbackIsDegrade() {
        CompletionVerdict verdict = verifier.verify(null,
                new ChatExecutionResult("o", "p", "a", "r", false), "本地兜底回答");
        assertThat(verdict.outcome())
                .isEqualTo(com.springclaw.runtime.contract.CompletionDecision.Outcome.DEGRADE);
        assertThat(verdict.reasonCode()).isEqualTo("MODEL_UNAVAILABLE_LOCAL_FALLBACK");
    }

    @Test
    void blankAnswerIsFail() {
        CompletionVerdict verdict = verifier.verify(null,
                new ChatExecutionResult("o", "p", "a", "r", true), "  ");
        assertThat(verdict.outcome())
                .isEqualTo(com.springclaw.runtime.contract.CompletionDecision.Outcome.FAIL);
        assertThat(verdict.reasonCode()).isEqualTo("EMPTY_ANSWER");
    }

    @Test
    void nullResultIsDegrade() {
        CompletionVerdict verdict = verifier.verify(null, null, "任何回答");
        assertThat(verdict.outcome())
                .isEqualTo(com.springclaw.runtime.contract.CompletionDecision.Outcome.DEGRADE);
    }
}
