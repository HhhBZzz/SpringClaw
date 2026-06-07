package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.TurnRequest;
import com.springclaw.service.agent.lifecycle.UtteranceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicUtteranceClassifierTest {

    private final DeterministicUtteranceClassifier classifier = new DeterministicUtteranceClassifier();

    @Test
    void shouldClassifySystemTimeQuestionAsControl() {
        TurnContext context = TurnContext.initial(new TurnRequest("s1", "api", "u1", "req-1", "今天日期是什么", "agent"));

        var utterance = classifier.classify(context);

        assertThat(utterance.type()).isEqualTo(UtteranceType.CONTROL);
        assertThat(utterance.confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void shouldClassifyGreetingAsChat() {
        TurnContext context = TurnContext.initial(new TurnRequest("s1", "api", "u1", "req-2", "你好", "agent"));

        var utterance = classifier.classify(context);

        assertThat(utterance.type()).isEqualTo(UtteranceType.CHAT);
        assertThat(utterance.confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void shouldClassifyShortTargetQuestionAsFollowUpWithoutResolvingIt() {
        TurnContext context = TurnContext.initial(new TurnRequest("s1", "api", "u1", "req-3", "北京呢", "agent"));

        var utterance = classifier.classify(context);

        assertThat(utterance.type()).isEqualTo(UtteranceType.FOLLOW_UP);
        assertThat(utterance.confidence()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void shouldClassifyCompleteQuestionAsNewIntentWithoutDomainKeywords() {
        TurnContext context = TurnContext.initial(new TurnRequest("s1", "api", "u1", "req-4", "哈尔滨怎样", "agent"));

        var utterance = classifier.classify(context);

        assertThat(utterance.type()).isEqualTo(UtteranceType.NEW_INTENT);
    }
}
