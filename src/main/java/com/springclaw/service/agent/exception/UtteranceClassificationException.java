package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public class UtteranceClassificationException extends RoutingException {
    public UtteranceClassificationException(String requestId, String message, Map<String, Object> metadata, Throwable cause) {
        super(requestId, AgentPhase.UTTERANCE_CLASSIFY, "UTTERANCE_CLASSIFICATION_FAILED", message, metadata, cause);
    }
}
