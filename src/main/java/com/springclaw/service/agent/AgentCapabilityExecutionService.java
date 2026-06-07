package com.springclaw.service.agent;

import com.springclaw.service.context.AssembledContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Compatibility adapter for the simplified engine.
 *
 * The real capability execution contract is {@link CapabilityExecutorRegistry}.
 * This class only adapts the shared {@link CapabilityResult} shape to the older
 * simplified-engine prompt context.
 */
@Service
public class AgentCapabilityExecutionService {

    private final CapabilityExecutorRegistry capabilityExecutorRegistry;
    private final boolean enabled;

    private AgentCapabilityExecutionService() {
        this.capabilityExecutorRegistry = null;
        this.enabled = false;
    }

    public AgentCapabilityExecutionService(CapabilityExecutorRegistry capabilityExecutorRegistry) {
        this.capabilityExecutorRegistry = capabilityExecutorRegistry;
        this.enabled = capabilityExecutorRegistry != null;
    }

    public static AgentCapabilityExecutionService noop() {
        return new AgentCapabilityExecutionService();
    }

    public List<AgentCapabilityResult> execute(AgentDecision decision,
                                               AssembledContext assembled,
                                               String requestId) {
        if (!enabled || decision == null || assembled == null || decision.isGeneral()
                || decision.requiresConfirmation() || decision.isDangerous()) {
            return List.of();
        }
        List<CapabilityResult> results = capabilityExecutorRegistry.execute(decision, assembled, requestId);
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(result -> new AgentCapabilityResult(
                        result.capabilityId(),
                        result.status(),
                        result.summary(),
                        result.payload()
                ))
                .toList();
    }
}
