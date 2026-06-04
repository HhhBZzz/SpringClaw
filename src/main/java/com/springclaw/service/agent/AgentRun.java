package com.springclaw.service.agent;

import com.springclaw.service.chat.impl.ChatExecutionResult;

import java.util.List;

/**
 * One complete backend Agent runtime execution.
 */
public record AgentRun(String requestId,
                       AgentDecision decision,
                       CapabilityPlan plan,
                       List<AgentStep> steps,
                       List<CapabilityResult> capabilityResults,
                       VerificationResult verification,
                       ChatExecutionResult executionResult) {

    public AgentRun {
        requestId = requestId == null ? "" : requestId;
        steps = steps == null ? List.of() : List.copyOf(steps);
        capabilityResults = capabilityResults == null ? List.of() : List.copyOf(capabilityResults);
    }
}
