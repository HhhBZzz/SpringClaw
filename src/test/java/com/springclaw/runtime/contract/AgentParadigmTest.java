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
    void allParadigmsAreImplemented() {
        // 全部 7 范式实现完成(地基 3 + REACT + PLAN_EXECUTE + REFLECTION + MULTI_AGENT),无占位剩余。
        // MULTI_AGENT 自其 Task 1 起接入后,AgentParadigm.values() 全 isImplemented=true。
        for (AgentParadigm paradigm : AgentParadigm.values()) {
            assertThat(paradigm.isImplemented())
                    .as(paradigm + " 应已实现")
                    .isTrue();
        }
    }

    @Test
    void eachParadigmHasDescription() {
        for (AgentParadigm paradigm : AgentParadigm.values()) {
            assertThat(paradigm.description()).isNotBlank();
        }
    }
}
