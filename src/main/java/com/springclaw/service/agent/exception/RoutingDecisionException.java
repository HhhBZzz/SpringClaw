package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class RoutingDecisionException extends RoutingException {
    public RoutingDecisionException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.INTENT_ROUTE, "ROUTING_DECISION_FAILED", message, metadata, cause);
    }
}
