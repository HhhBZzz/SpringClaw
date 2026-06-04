package com.springclaw.service.agent;

import com.springclaw.service.context.AssembledContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Registry for pluggable Agent capability executors.
 */
@Service
public class CapabilityExecutorRegistry {

    private final List<CapabilityExecutor> executors;

    public CapabilityExecutorRegistry(List<CapabilityExecutor> executors) {
        this.executors = executors == null
                ? List.of()
                : executors.stream().sorted(Comparator.comparing(CapabilityExecutor::toolset)).toList();
    }

    public CapabilityPlan plan(AgentDecision decision) {
        if (decision == null) {
            return new CapabilityPlan("unknown", "ask_clarification", List.of(), List.of(), "read", false, "未生成 Agent 决策。");
        }
        List<String> toolsets = executors.stream()
                .filter(executor -> executor.supports(decision))
                .map(CapabilityExecutor::toolset)
                .distinct()
                .toList();
        return new CapabilityPlan(
                decision.intent(),
                decision.executionPath(),
                decision.selectedCapabilities(),
                toolsets,
                decision.riskLevel(),
                decision.requiresConfirmation(),
                decision.reason()
        );
    }

    public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
        if (decision == null || decision.isGeneral() || decision.requiresConfirmation() || decision.isDangerous()) {
            return List.of();
        }
        List<CapabilityResult> results = new ArrayList<>();
        for (CapabilityExecutor executor : executors) {
            if (!executor.supports(decision)) {
                continue;
            }
            List<CapabilityResult> executorResults = executor.execute(decision, assembled, requestId);
            if (executorResults != null && !executorResults.isEmpty()) {
                results.addAll(executorResults);
            }
        }
        return List.copyOf(results);
    }
}
