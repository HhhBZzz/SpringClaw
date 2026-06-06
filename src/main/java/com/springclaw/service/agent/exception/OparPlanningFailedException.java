package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class OparPlanningFailedException extends OparException {
    public OparPlanningFailedException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.OPAR_PLAN, "OPAR_PLANNING_FAILED", message, metadata, cause);
    }
}
