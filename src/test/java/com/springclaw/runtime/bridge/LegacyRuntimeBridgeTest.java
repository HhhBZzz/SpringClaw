package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.RuntimeStrategy;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyRuntimeBridgeTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptedDelegatesToCanonicalCoordinator() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        LegacyRuntimeBridge bridge = new DefaultLegacyRuntimeBridge(
                new RunCoordinator(store)
        );

        bridge.accepted(new RunAcceptance(
                RUN_ID, "session-1", "api", "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                ),
                "USER", "hello",
                "agent", Instant.parse("2026-06-21T00:00:00Z"),
                Instant.parse("2026-06-21T00:05:00Z")
        ));

        assertThat(store.requireByRunId(RUN_ID).runId()).isEqualTo(RUN_ID);
    }

    @Test
    void bridgeIsNotRuntimeStrategyAndHasNoLegacyExecutionDependencies() {
        assertThat(RuntimeStrategy.class.isAssignableFrom(LegacyRuntimeBridge.class))
                .isFalse();

        Set<String> signatureTypes = Arrays.stream(LegacyRuntimeBridge.class.getMethods())
                .flatMap(LegacyRuntimeBridgeTest::signatureTypes)
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertThat(signatureTypes).noneMatch(type ->
                type.contains("SseEmitter")
                        || type.contains("ChatResponse")
                        || type.contains("RabbitTemplate")
                        || type.contains("AgentEngine")
                        || type.contains("ChatResultPersister")
                        || type.contains("ToolInvoker")
                        || type.contains("WorkspaceGitGuard")
        );
    }

    private static Stream<Class<?>> signatureTypes(Method method) {
        return Stream.concat(
                Stream.of(method.getReturnType()),
                Arrays.stream(method.getParameterTypes())
        );
    }
}
