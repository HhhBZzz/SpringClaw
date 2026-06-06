package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.AgentPhaseOrderViolationException;
import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.TurnRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DefaultTurnPipeline implements TurnPipeline {

    private final List<TurnStage> stages;

    public DefaultTurnPipeline(List<TurnStage> stages) {
        this.stages = List.copyOf(stages == null ? List.of() : stages);
        validateRegistrationOrder(this.stages);
    }

    @Override
    public TurnContext prepare(TurnRequest request) {
        TurnContext context = TurnContext.initial(request);
        for (TurnStage stage : stages) {
            if (!stage.supports(context)) {
                continue;
            }
            assertLegalAdvance(context.runState().currentPhase(), stage.phase());
            TurnContext next = stage.apply(context);
            if (next == null) {
                throw new AgentPhaseOrderViolationException(
                        "TurnStage 不允许返回 null: " + stage.phase().name(),
                        Map.of("phase", stage.phase().name())
                );
            }
            context = next;
        }
        return context;
    }

    private void validateRegistrationOrder(List<TurnStage> registeredStages) {
        AgentPhase previous = null;
        for (TurnStage stage : registeredStages) {
            if (stage == null) {
                continue;
            }
            AgentPhase current = stage.phase();
            if (previous != null && current.ordinal() <= previous.ordinal()) {
                throw new AgentPhaseOrderViolationException(
                        "AgentPhase 注册顺序非法: " + previous.name() + " -> " + current.name(),
                        Map.of("previousPhase", previous.name(), "currentPhase", current.name())
                );
            }
            previous = current;
        }
    }

    private void assertLegalAdvance(AgentPhase current, AgentPhase next) {
        AgentPhase currentPhase = current == null ? AgentPhase.INPUT_NORMALIZE : current;
        if (next.ordinal() < currentPhase.ordinal()) {
            throw new AgentPhaseOrderViolationException(
                    "AgentPhase 流转顺序非法: " + currentPhase.name() + " -> " + next.name(),
                    Map.of("currentPhase", currentPhase.name(), "nextPhase", next.name())
            );
        }
    }
}
