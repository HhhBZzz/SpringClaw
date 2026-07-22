package com.springclaw.service.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentParadigmTest {

    @Test
    void definesAllSevenParadigms() {
        assertThat(AgentParadigm.values())
                .containsExactlyInAnyOrder(
                        AgentParadigm.SINGLE_TURN,
                        AgentParadigm.OPAR,
                        AgentParadigm.AUTONOMOUS_LOOP,
                        AgentParadigm.REACT,
                        AgentParadigm.PLAN_EXECUTE,
                        AgentParadigm.REFLECTION,
                        AgentParadigm.MULTI_AGENT
                );
    }

    @Test
    void firstThreeAreImplementedRestArePlaceholders() {
        assertThat(AgentParadigm.SINGLE_TURN.isImplemented()).isTrue();
        assertThat(AgentParadigm.OPAR.isImplemented()).isTrue();
        assertThat(AgentParadigm.AUTONOMOUS_LOOP.isImplemented()).isTrue();

        assertThat(AgentParadigm.REACT.isImplemented()).isFalse();
        assertThat(AgentParadigm.PLAN_EXECUTE.isImplemented()).isFalse();
        assertThat(AgentParadigm.REFLECTION.isImplemented()).isFalse();
        assertThat(AgentParadigm.MULTI_AGENT.isImplemented()).isFalse();
    }

    @Test
    void eachParadigmHasDescription() {
        for (AgentParadigm paradigm : AgentParadigm.values()) {
            assertThat(paradigm.description()).isNotBlank();
        }
    }
}
