package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStrategyContractTest {

    @Test
    void strategyBoundaryContainsNoTransportOrPersistenceTypes() {
        Set<String> forbiddenFragments = Set.of(
                "SseEmitter",
                "RabbitTemplate",
                "HttpServlet",
                "Repository",
                "Mapper"
        );

        String signatureText = Arrays.stream(RuntimeStrategy.class.getDeclaredMethods())
                .map(method -> method.toGenericString())
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(forbiddenFragments)
                .allSatisfy(fragment -> assertThat(signatureText).doesNotContain(fragment));
    }

    @Test
    void strategyExposesOnlyIdentityCapabilitiesExecuteAndResume() {
        assertThat(Arrays.stream(RuntimeStrategy.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("strategyId", "capabilities", "execute", "resume");
    }

    @Test
    void strategyExecutionsReturnUnpersistedEventDrafts() throws Exception {
        String eventsType = RuntimeStrategy.StrategyExecution.class
                .getDeclaredMethod("events")
                .getGenericReturnType()
                .getTypeName();

        assertThat(eventsType)
                .contains("RunEvent$Draft")
                .doesNotContain("java.util.List<com.springclaw.runtime.contract.RunEvent>");
    }
}
