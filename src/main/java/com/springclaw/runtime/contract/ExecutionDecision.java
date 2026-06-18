package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Normalized execution decision produced by {@code RuntimeDecisionService} from the
 * accepted input, context snapshot, capability catalog, model availability, and
 * policy summary. See unified-runtime architecture spec § 7.4.
 *
 * <p>{@code riskSummary} is advisory only — it cannot authorize a side effect.
 * Actual invocation risk classification belongs to {@code ToolGateway} /
 * {@code ToolRuntimeAspect}. This decision cannot mark a run complete and carries
 * no transport behavior.
 */
public record ExecutionDecision(
        String runId,
        String intent,
        String goal,
        String responseMode,
        String riskSummary,
        List<String> selectedCapabilityIds,
        List<String> requestedInvocations,
        Map<String, String> strategyRequirements,
        List<String> missingInputs,
        double confidence,
        String reason,
        String decisionSource,
        Instant decidedAt
) {
    public ExecutionDecision {
        runId = requireText(runId, "runId");
        intent = requireText(intent, "intent");
        goal = Objects.requireNonNullElse(goal, "");
        responseMode = requireText(responseMode, "responseMode");
        riskSummary = Objects.requireNonNullElse(riskSummary, "");
        selectedCapabilityIds = copy(selectedCapabilityIds);
        requestedInvocations = copy(requestedInvocations);
        strategyRequirements = strategyRequirements == null ? Map.of() : Map.copyOf(strategyRequirements);
        missingInputs = copy(missingInputs);
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        reason = Objects.requireNonNullElse(reason, "");
        decisionSource = requireText(decisionSource, "decisionSource");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    private static List<String> copy(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
