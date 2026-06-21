package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.chat.impl.ChatContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public final class LegacyExecutionDecisionAdapter {

    public ExecutionDecision adapt(ChatContext context, Instant decidedAt) {
        AgentDecision decision = context.decision() == null
                ? AgentDecision.general(context.routingReason())
                : context.decision();
        return new ExecutionDecision(
                context.requestId(),
                decision.intent(),
                context.effectiveUserMessage(),
                context.responseMode(),
                decision.riskLevel(),
                decision.selectedCapabilities(),
                List.of(),
                Map.of(
                        "executionPath", decision.executionPath(),
                        "legacyExecutionMode", context.executionMode(),
                        "legacyRoutingReason", context.routingReason()
                ),
                List.of(),
                0.0,
                decision.reason(),
                "legacy-agent-decision",
                decidedAt
        );
    }
}
