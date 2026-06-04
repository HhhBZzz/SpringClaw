package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.CapabilityResult;

import java.util.concurrent.Callable;

abstract class AbstractCapabilityExecutor {

    private static final int MAX_PAYLOAD_CHARS = 5000;

    protected CapabilityResult run(String capabilityId,
                                   String toolset,
                                   String riskLevel,
                                   String summary,
                                   Callable<String> action) {
        long startedAt = System.currentTimeMillis();
        try {
            String payload = action.call();
            return new CapabilityResult(capabilityId, toolset, "success", summary, trim(payload), System.currentTimeMillis() - startedAt, riskLevel);
        } catch (Exception ex) {
            return new CapabilityResult(capabilityId, toolset, "failed", summary, ex.getClass().getSimpleName() + ": " + ex.getMessage(), System.currentTimeMillis() - startedAt, riskLevel);
        }
    }

    protected boolean intent( com.springclaw.service.agent.AgentDecision decision, String intent) {
        return decision != null && intent.equalsIgnoreCase(decision.intent());
    }

    protected String safe(String value) {
        return value == null ? "" : value;
    }

    private String trim(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= MAX_PAYLOAD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PAYLOAD_CHARS) + "\n...<TRUNCATED>";
    }
}
