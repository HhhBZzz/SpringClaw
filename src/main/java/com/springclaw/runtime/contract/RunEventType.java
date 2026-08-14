package com.springclaw.runtime.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Canonical run event families. The wire name (dotted, e.g. {@code context.ready})
 * is the stable serialization contract across trace, audit, and transport projections
 * — see unified-runtime architecture spec § 7.9.
 *
 * <p>读侧别名：{@code verification.completed} 是 {@code verification.started} 的
 * 历史名（该事件实际在进入 VERIFYING 时发射）。存量持久化记录按别名读取，写入一律用新名。</p>
 */
public enum RunEventType {
    RUN_CREATED("run.created"),
    CONTEXT_READY("context.ready"),
    DECISION_MADE("decision.made"),
    STRATEGY_STARTED("strategy.started"),
    TURN_STARTED("turn.started"),
    STEP_STARTED("step.started"),
    MODEL_CALLED("model.called"),
    TOOL_REQUESTED("tool.requested"),
    CONFIRMATION_REQUIRED("confirmation.required"),
    CONFIRMATION_APPROVED("confirmation.approved"),
    CONFIRMATION_REJECTED("confirmation.rejected"),
    TOOL_STARTED("tool.started"),
    TOOL_SUCCEEDED("tool.succeeded"),
    TOOL_FAILED("tool.failed"),
    STEP_COMPLETED("step.completed"),
    TURN_COMPLETED("turn.completed"),
    VERIFICATION_STARTED("verification.started"),
    MEMORY_EXTRACTED("memory.extracted"),
    REFLECTED("reflect.completed"),
    ANSWER_COMPOSED("answer.composed"),
    RUN_COMPLETED("run.completed"),
    RUN_DEGRADED("run.degraded"),
    RUN_FAILED("run.failed"),
    DELIVERY_ATTEMPTED("delivery.attempted"),
    DELIVERY_FAILED("delivery.failed");

    private static final Map<String, RunEventType> WIRE_LOOKUP = Stream
            .concat(
                    Arrays.stream(values()).map(type -> Map.entry(type.wireName(), type)),
                    // 历史别名：写新读旧
                    Stream.of(Map.entry("verification.completed", VERIFICATION_STARTED))
            )
            .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

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
        RunEventType type = WIRE_LOOKUP.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown run event type: " + value);
        }
        return type;
    }
}
