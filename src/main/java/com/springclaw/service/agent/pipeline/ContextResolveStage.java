package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.TurnContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(AgentPhaseOrder.CONTEXT_RESOLVE)
public class ContextResolveStage implements TurnStage {

    private final ContextualResolver resolver;
    private final ContextResolutionPolicy policy;

    public ContextResolveStage(ContextualResolver resolver, ContextResolutionPolicy policy) {
        this.resolver = resolver;
        this.policy = policy;
    }

    @Override
    public AgentPhase phase() {
        return AgentPhase.CONTEXT_RESOLVE;
    }

    @Override
    public boolean supports(TurnContext context) {
        return policy.canResolve(context.utterance(), context.intentDecision());
    }

    @Override
    public TurnContext apply(TurnContext context) {
        TurnContext advanced = context.advanceTo(phase());
        return advanced.withResolvedInput(resolver.resolve(advanced));
    }
}
