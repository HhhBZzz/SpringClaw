package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, explainable snapshot of all context authorized for one run.
 *
 * <p>Assembled exactly once at {@code CREATED -> CONTEXT_READY} by
 * {@code ContextSnapshotFactory}. Retries and confirmation resumes reuse this
 * snapshot — Advisors may only project it into model requests, never retrieve a
 * second memory view. See unified-runtime architecture spec § 7.3.
 */
public record ContextSnapshot(
        String runId,
        String sessionKey,
        String sessionOwnerUserId,
        String channel,
        String userId,
        String roleCode,
        String originalMessage,
        String effectiveMessage,
        String systemPrompt,
        String memoryBankText,
        List<String> shortTermEvents,
        List<String> semanticRecallItems,
        List<String> activeLearningRules,
        List<String> allowedCapabilities,
        Map<String, String> providerSnapshot,
        Map<String, String> contextSourceSummary,
        MemoryFrame memoryFrame,
        Instant capturedAt,
        String snapshotHash
) {
    public ContextSnapshot {
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        sessionOwnerUserId = requireText(sessionOwnerUserId, "sessionOwnerUserId");
        channel = requireText(channel, "channel");
        userId = requireText(userId, "userId");
        roleCode = requireText(roleCode, "roleCode");
        originalMessage = Objects.requireNonNullElse(originalMessage, "");
        effectiveMessage = Objects.requireNonNullElse(effectiveMessage, "");
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        memoryBankText = Objects.requireNonNullElse(memoryBankText, "");
        shortTermEvents = copy(shortTermEvents);
        semanticRecallItems = copy(semanticRecallItems);
        activeLearningRules = copy(activeLearningRules);
        allowedCapabilities = copy(allowedCapabilities);
        providerSnapshot = providerSnapshot == null ? Map.of() : Map.copyOf(providerSnapshot);
        contextSourceSummary = contextSourceSummary == null ? Map.of() : Map.copyOf(contextSourceSummary);
        memoryFrame = Objects.requireNonNull(memoryFrame, "memoryFrame");
        if (!runId.equals(memoryFrame.runId())) {
            throw new IllegalArgumentException("MemoryFrame runId must match ContextSnapshot runId");
        }
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        snapshotHash = requireText(snapshotHash, "snapshotHash");
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
