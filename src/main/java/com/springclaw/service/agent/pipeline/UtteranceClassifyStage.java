package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.TurnContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(AgentPhaseOrder.UTTERANCE_CLASSIFY)
public class UtteranceClassifyStage implements TurnStage {

    private final UtteranceClassifier classifier;

    public UtteranceClassifyStage(UtteranceClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public AgentPhase phase() {
        return AgentPhase.UTTERANCE_CLASSIFY;
    }

    @Override
    public boolean supports(TurnContext context) {
        return true;
    }

    @Override
    public TurnContext apply(TurnContext context) {
        TurnContext advanced = context.advanceTo(phase());
        return advanced.withUtterance(classifier.classify(advanced));
    }
}
