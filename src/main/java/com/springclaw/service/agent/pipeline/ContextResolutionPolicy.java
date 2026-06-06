package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.IntentDecision;
import com.springclaw.service.agent.lifecycle.Utterance;

public interface ContextResolutionPolicy {
    boolean canResolve(Utterance utterance, IntentDecision previousIntent);
}
