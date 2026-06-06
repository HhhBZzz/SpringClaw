package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class SlotValidationException extends ParameterException {
    public SlotValidationException(String requestId, String message, Map<String, Object> metadata) {
        super(requestId, AgentPhase.SLOT_BIND, "SLOT_VALIDATION_FAILED", message, metadata, null);
    }
}
