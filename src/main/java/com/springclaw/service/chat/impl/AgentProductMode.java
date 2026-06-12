package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Maps internal routing signals to a product-facing mode.
 */
final class AgentProductMode {

    static final String QUICK_ANSWER = "quick_answer";
    static final String AGENT_ANALYSIS = "agent_analysis";
    static final String EXECUTION_TASK = "execution_task";

    private AgentProductMode() {
    }

    static String resolve(String responseMode,
                          String executionMode,
                          String intent,
                          AgentDecision decision) {
        if (isExecutionTask(decision)) {
            return EXECUTION_TASK;
        }
        if (isGeneral(responseMode, executionMode, intent, decision)) {
            return QUICK_ANSWER;
        }
        return AGENT_ANALYSIS;
    }

    static String resolve(ChatContext context) {
        if (context == null) {
            return QUICK_ANSWER;
        }
        return resolve(context.responseMode(), context.executionMode(), context.intent(), context.decision());
    }

    private static boolean isExecutionTask(AgentDecision decision) {
        if (decision == null) {
            return false;
        }
        String riskLevel = normalize(decision.riskLevel());
        return decision.requiresConfirmation()
                || decision.isDangerous()
                || "write".equals(riskLevel)
                || "side_effect".equals(riskLevel);
    }

    private static boolean isGeneral(String responseMode,
                                     String executionMode,
                                     String intent,
                                     AgentDecision decision) {
        String normalizedResponseMode = normalize(responseMode);
        String normalizedExecutionMode = normalize(executionMode);
        String normalizedIntent = normalize(intent);
        boolean generalDecision = decision == null || decision.isGeneral();
        boolean generalIntent = !StringUtils.hasText(normalizedIntent) || "general".equals(normalizedIntent);
        boolean simplifiedMode = !StringUtils.hasText(normalizedExecutionMode) || "simplified".equals(normalizedExecutionMode);
        boolean quickResponseMode = !StringUtils.hasText(normalizedResponseMode)
                || "agent".equals(normalizedResponseMode)
                || "fast".equals(normalizedResponseMode);
        return generalDecision && generalIntent && simplifiedMode && quickResponseMode;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
