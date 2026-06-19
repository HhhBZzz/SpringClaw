package com.springclaw.runtime.contract;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.context.AssembledContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only translator proving current production records can populate the new
 * unified-runtime contracts before production adapters are introduced.
 *
 * <p>This class lives under {@code src/test} on purpose — it is evidence of
 * compatibility, not a production bridge. Real adapters arrive in the
 * {@code unified-runtime-legacy-bridge} plan.
 */
final class LegacyRuntimeContractFixtures {

    private static final String MEMORY_BANK_HEADING = "# 项目记忆（Memory Bank）";
    private static final String EVENT_CONTEXT_HEADING = "# 短期会话上下文（事件流）";

    private LegacyRuntimeContractFixtures() {
    }

    static ContextSnapshot contextSnapshot(
            String runId,
            AssembledContext assembled,
            String roleCode,
            Instant capturedAt
    ) {
        Objects.requireNonNull(assembled, "assembled");
        return new ContextSnapshot(
                runId,
                assembled.sessionKey(),
                assembled.userId(),
                assembled.channel() == null ? "api" : assembled.channel(),
                assembled.userId(),
                roleCode,
                assembled.question(),
                assembled.question(),
                "",                              // systemPrompt: legacy AssembledContext does not carry it
                memoryBankText(assembled.observePrompt()),
                assembled.eventContext() == null || assembled.eventContext().isBlank()
                        ? List.of() : List.of(assembled.eventContext()),
                assembled.semanticContext() == null || assembled.semanticContext().isBlank()
                        ? List.of() : List.of(assembled.semanticContext()),
                List.of(),                       // activeLearningRules: legacy does not expose separately
                // AssembledContext has no capability catalog; the production bridge
                // must source allowed capabilities elsewhere instead of guessing.
                List.of(),
                Map.of(),
                Map.of("schema", "springclaw.context-source.v1",
                        "learningActive", String.valueOf(assembled.memoryLearningActiveCount()),
                        "learningFiltered", String.valueOf(assembled.memoryLearningFilteredCount())),
                capturedAt,
                snapshotHash(runId, assembled, capturedAt)
        );
    }

    static ExecutionDecision executionDecision(
            String runId,
            AgentDecision legacy,
            String responseMode,
            Instant decidedAt
    ) {
        Objects.requireNonNull(legacy, "legacy");
        return new ExecutionDecision(
                runId,
                legacy.intent(),
                legacy.executionPath() == null ? "" : legacy.executionPath(),
                responseMode,
                legacy.riskLevel() == null ? "" : legacy.riskLevel(),
                legacy.selectedCapabilities(),
                List.of(),                       // requestedInvocations: legacy does not separate
                Map.of(
                        "requiresConfirmation", String.valueOf(legacy.requiresConfirmation()),
                        "executionPath", legacy.executionPath() == null ? "" : legacy.executionPath()
                ),
                List.of(),                       // missingInputs: legacy does not carry
                1.0,                             // confidence: legacy has no confidence signal
                legacy.reason() == null ? "" : legacy.reason(),
                "legacy-agent-decision",
                decidedAt
        );
    }

    private static String memoryBankText(String observePrompt) {
        if (observePrompt == null || observePrompt.isBlank()) {
            return "";
        }
        int headingStart = observePrompt.indexOf(MEMORY_BANK_HEADING);
        if (headingStart < 0) {
            return "";
        }
        int bodyStart = headingStart + MEMORY_BANK_HEADING.length();
        int bodyEnd = observePrompt.indexOf(EVENT_CONTEXT_HEADING, bodyStart);
        return (bodyEnd < 0
                ? observePrompt.substring(bodyStart)
                : observePrompt.substring(bodyStart, bodyEnd)).trim();
    }

    private static String snapshotHash(String runId, AssembledContext assembled, Instant capturedAt) {
        // Lightweight stable hash for fixture use; production adapters will use the
        // canonical ContextSnapshotHasher when it is introduced.
        String material = runId + "|" + assembled.sessionKey() + "|" + assembled.userId()
                + "|" + assembled.question() + "|" + capturedAt;
        return Integer.toHexString(material.hashCode());
    }
}
