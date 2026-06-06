package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class AgentPhaseOrderViolationException extends AgentExecutionException {
    public AgentPhaseOrderViolationException(String message, Map<String, Object> metadata) {
        super("", AgentPhase.INPUT_NORMALIZE, "AGENT_PHASE_ORDER_VIOLATION", ErrorSeverity.FATAL, message, metadata, null);
    }
}
