package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class RequiredSlotMissingException extends ParameterException {
    public RequiredSlotMissingException(String requestId, String message, Map<String, Object> metadata) {
        super(requestId, AgentPhase.SLOT_BIND, "REQUIRED_SLOT_MISSING", message, metadata, null);
    }
}
