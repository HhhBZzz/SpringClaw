package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.ResolvedInput;
import com.springclaw.service.agent.lifecycle.TurnContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(AgentPhaseOrder.CONTROL_BYPASS)
public class ControlBypassStage implements TurnStage {

    private final ContextResolutionPolicy policy;

    public ControlBypassStage(ContextResolutionPolicy policy) {
        this.policy = policy;
    }

    @Override
    public AgentPhase phase() {
        return AgentPhase.CONTROL_BYPASS;
    }

    @Override
    public boolean supports(TurnContext context) {
        return !policy.canResolve(context.utterance(), context.intentDecision());
    }

    @Override
    public TurnContext apply(TurnContext context) {
        TurnContext advanced = context.advanceTo(phase());
        String type = advanced.utterance() == null ? "UNKNOWN" : advanced.utterance().type().name();
        return advanced.withResolvedInput(ResolvedInput.bypassed(
                advanced.rawInput().text(),
                "话语类型=" + type + "，不进入追问消解。"
        ));
    }
}
