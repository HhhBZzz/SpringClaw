package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.TurnContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(AgentPhaseOrder.INPUT_NORMALIZE)
public class InputNormalizeStage implements TurnStage {
    @Override
    public AgentPhase phase() {
        return AgentPhase.INPUT_NORMALIZE;
    }

    @Override
    public boolean supports(TurnContext context) {
        return true;
    }

    @Override
    public TurnContext apply(TurnContext context) {
        return context.advanceTo(phase());
    }
}
