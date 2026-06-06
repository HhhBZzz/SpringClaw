package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.IntentDecision;
import com.springclaw.service.agent.lifecycle.Utterance;
import com.springclaw.service.agent.lifecycle.UtteranceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class DefaultContextResolutionPolicy implements ContextResolutionPolicy {

    private static final Set<UtteranceType> RESOLVABLE_TYPES = EnumSet.of(
            UtteranceType.FOLLOW_UP,
            UtteranceType.SLOT_VALUE
    );

    private final double confidenceThreshold;

    public DefaultContextResolutionPolicy(
            @Value("${springclaw.agent.context-resolution-confidence-threshold:0.72}") double confidenceThreshold) {
        this.confidenceThreshold = Math.max(0.0, Math.min(1.0, confidenceThreshold));
    }

    @Override
    public boolean canResolve(Utterance utterance, IntentDecision previousIntent) {
        if (utterance == null || !RESOLVABLE_TYPES.contains(utterance.type())) {
            return false;
        }
        return utterance.confidence() >= confidenceThreshold;
    }
}
