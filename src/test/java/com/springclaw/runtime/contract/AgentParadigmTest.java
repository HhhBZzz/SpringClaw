package com.springclaw.runtime.contract;

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
    void implementedParadigmsVsPlaceholders() {
        // 已实现(地基 4 个 + REACT Task 1 + PLAN_EXECUTE Task 2 + REFLECTION Task 1)
        assertThat(AgentParadigm.SINGLE_TURN.isImplemented()).isTrue();
        assertThat(AgentParadigm.OPAR.isImplemented()).isTrue();
        assertThat(AgentParadigm.AUTONOMOUS_LOOP.isImplemented()).isTrue();
        assertThat(AgentParadigm.REACT.isImplemented()).isTrue();
        assertThat(AgentParadigm.PLAN_EXECUTE.isImplemented()).isTrue();
        assertThat(AgentParadigm.REFLECTION.isImplemented()).isTrue();

        // 占位(待增量接入)
        assertThat(AgentParadigm.MULTI_AGENT.isImplemented()).isFalse();
    }

    @Test
    void eachParadigmHasDescription() {
        for (AgentParadigm paradigm : AgentParadigm.values()) {
            assertThat(paradigm.description()).isNotBlank();
        }
    }
}
