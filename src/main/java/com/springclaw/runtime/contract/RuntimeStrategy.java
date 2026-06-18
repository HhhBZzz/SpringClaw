package com.springclaw.runtime.contract;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boundary contract for a runtime strategy.
 *
 * <p>A strategy executes one selected runtime approach and emits evidence-bearing
 * events. It is selected by {@code RuntimeStrategyRegistry} from an
 * {@link ExecutionDecision} — it never self-selects from raw user text. Strategies
 * MUST NOT mutate run status, persist, compose final answers, terminate transports,
 * release session locks, or execute write tools outside {@code ToolGateway}. See
 * unified-runtime architecture spec § 7.5 and § 15 (architectural rules 1-4).
 *
 * <p>The API-shape contract is enforced by {@code RuntimeStrategyContractTest}:
 * only {@code strategyId}, {@code capabilities}, {@code execute}, {@code resume}
 * are exposed, and no method signature may reference transport or persistence types.
 */
public interface RuntimeStrategy {

    String strategyId();

    StrategyCapabilities capabilities();

    StrategyExecution execute(RunExecutionContext context);

    StrategyExecution resume(RunExecutionContext context, ToolInvocation.Outcome outcome);

    record StrategyCapabilities(Set<String> capabilityIds, boolean resumable) {
        public StrategyCapabilities {
            capabilityIds = capabilityIds == null ? Set.of() : Set.copyOf(capabilityIds);
        }
    }

    record RunExecutionContext(
            RunState runState,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision
    ) {
        public RunExecutionContext {
            if (runState == null || contextSnapshot == null || executionDecision == null) {
                throw new IllegalArgumentException("runState, contextSnapshot, and executionDecision are required");
            }
            if (!runState.runId().equals(contextSnapshot.runId())
                    || !runState.runId().equals(executionDecision.runId())) {
                throw new IllegalArgumentException("execution context runId values must match");
            }
        }
    }

    record StrategyExecution(
            List<RunEvent> events,
            List<String> evidence,
            ToolInvocation requestedToolInvocation,
            Map<String, Long> modelUsage,
            String continuationToken,
            RunState.Failure strategyFailure
    ) {
        public StrategyExecution {
            events = events == null ? List.of() : List.copyOf(events);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            modelUsage = modelUsage == null ? Map.of() : Map.copyOf(modelUsage);
        }
    }
}
