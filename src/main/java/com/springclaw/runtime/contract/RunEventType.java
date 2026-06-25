package com.springclaw.runtime.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Canonical run event families. The wire name (dotted, e.g. {@code context.ready})
 * is the stable serialization contract across trace, audit, and transport projections
 * — see unified-runtime architecture spec § 7.9.
 */
public enum RunEventType {
    RUN_CREATED("run.created"),
    CONTEXT_READY("context.ready"),
    DECISION_MADE("decision.made"),
    STRATEGY_STARTED("strategy.started"),
    MODEL_CALLED("model.called"),
    TOOL_REQUESTED("tool.requested"),
    CONFIRMATION_REQUIRED("confirmation.required"),
    CONFIRMATION_APPROVED("confirmation.approved"),
    CONFIRMATION_REJECTED("confirmation.rejected"),
    TOOL_STARTED("tool.started"),
    TOOL_SUCCEEDED("tool.succeeded"),
    TOOL_FAILED("tool.failed"),
    VERIFICATION_COMPLETED("verification.completed"),
    ANSWER_COMPOSED("answer.composed"),
    RUN_COMPLETED("run.completed"),
    RUN_DEGRADED("run.degraded"),
    RUN_FAILED("run.failed"),
    DELIVERY_ATTEMPTED("delivery.attempted"),
    DELIVERY_FAILED("delivery.failed");

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static RunEventType fromWireName(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown run event type: " + value));
    }
}
