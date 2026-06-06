package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.Utterance;
import com.springclaw.service.agent.lifecycle.UtteranceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextResolutionPolicyTest {

    private final DefaultContextResolutionPolicy policy = new DefaultContextResolutionPolicy(0.72);

    @Test
    void shouldBypassControlAndChatInputs() {
        assertThat(policy.canResolve(new Utterance(UtteranceType.CONTROL, 0.98, "system control"), null)).isFalse();
        assertThat(policy.canResolve(new Utterance(UtteranceType.CHAT, 0.96, "small talk"), null)).isFalse();
    }

    @Test
    void shouldOnlyResolveHighConfidenceFollowUpOrSlotValue() {
        assertThat(policy.canResolve(new Utterance(UtteranceType.FOLLOW_UP, 0.81, "short target"), null)).isTrue();
        assertThat(policy.canResolve(new Utterance(UtteranceType.SLOT_VALUE, 0.78, "slot value"), null)).isTrue();
        assertThat(policy.canResolve(new Utterance(UtteranceType.FOLLOW_UP, 0.41, "weak target"), null)).isFalse();
        assertThat(policy.canResolve(new Utterance(UtteranceType.NEW_INTENT, 0.91, "explicit request"), null)).isFalse();
    }
}
