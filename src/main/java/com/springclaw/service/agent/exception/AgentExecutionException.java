package com.springclaw.service.agent.exception;

import com.springclaw.service.agent.lifecycle.AgentPhase;

import java.util.Map;

public abstract class AgentExecutionException extends RuntimeException {

    private final String requestId;
    private final AgentPhase phase;
    private final String errorCode;
    private final ErrorSeverity severity;
    private final String userVisibleMessage;
    private final Map<String, Object> metadata;

    protected AgentExecutionException(String requestId,
                                      AgentPhase phase,
                                      String errorCode,
                                      ErrorSeverity severity,
                                      String userVisibleMessage,
                                      Map<String, Object> metadata,
                                      Throwable cause) {
        super(userVisibleMessage, cause);
        this.requestId = requestId == null ? "" : requestId.trim();
        this.phase = phase == null ? AgentPhase.FINALIZE : phase;
        this.errorCode = errorCode == null ? "AGENT_EXECUTION_FAILED" : errorCode.trim();
        this.severity = severity == null ? ErrorSeverity.FAILED : severity;
        this.userVisibleMessage = userVisibleMessage == null ? "" : userVisibleMessage.trim();
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String requestId() {
        return requestId;
    }

    public AgentPhase phase() {
        return phase;
    }

    public String errorCode() {
        return errorCode;
    }

    public ErrorSeverity severity() {
        return severity;
    }

    public String userVisibleMessage() {
        return userVisibleMessage;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public int responseCode() {
        return 46000;
    }
}
