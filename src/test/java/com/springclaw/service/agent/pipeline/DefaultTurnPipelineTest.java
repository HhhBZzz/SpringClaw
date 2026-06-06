package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.AgentExecutionException;
import com.springclaw.service.agent.exception.AgentPhaseOrderViolationException;
import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.TurnRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTurnPipelineTest {

    @Test
    void shouldRejectOutOfOrderStagesAtConstruction() {
        assertThatThrownBy(() -> new DefaultTurnPipeline(List.of(
                stage(AgentPhase.INTENT_ROUTE),
                stage(AgentPhase.UTTERANCE_CLASSIFY)
        ))).isInstanceOf(AgentPhaseOrderViolationException.class)
                .hasMessageContaining("INTENT_ROUTE")
                .hasMessageContaining("UTTERANCE_CLASSIFY");
    }

    @Test
    void shouldApplyStagesInAgentPhaseOrder() {
        TurnPipeline pipeline = new DefaultTurnPipeline(List.of(
                stage(AgentPhase.INPUT_NORMALIZE),
                stage(AgentPhase.UTTERANCE_CLASSIFY),
                stage(AgentPhase.CONTROL_BYPASS)
        ));

        TurnContext result = pipeline.prepare(new TurnRequest("s1", "api", "u1", "req-1", "你好", "agent"));

        assertThat(result.runState().currentPhase()).isEqualTo(AgentPhase.CONTROL_BYPASS);
        assertThat(result.runState().traces()).hasSize(3);
    }

    private TurnStage stage(AgentPhase phase) {
        return new TurnStage() {
            @Override
            public AgentPhase phase() {
                return phase;
            }

            @Override
            public boolean supports(TurnContext context) {
                return true;
            }

            @Override
            public TurnContext apply(TurnContext context) throws AgentExecutionException {
                return context.advanceTo(phase);
            }
        };
    }
}
