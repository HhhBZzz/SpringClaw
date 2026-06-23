package com.springclaw.service.memory;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryWriteCommand(
        String logicalMemoryId,
        MemoryType memoryType,
        MemoryScope scope,
        String content,
        String summary,
        String sourceRunId,
        List<String> sourceEventIds,
        List<String> evidenceRefs,
        List<String> tags,
        double importance,
        double confidence,
        MemoryStatus requestedStatus,
        Instant validFrom,
        Instant validUntil,
        String sourceKind,
        String sourceIdentity,
        String extractionPolicyVersion
) {
    public MemoryWriteCommand {
        logicalMemoryId = requireText(logicalMemoryId, "logicalMemoryId");
        memoryType = Objects.requireNonNull(memoryType, "memoryType");
        scope = Objects.requireNonNull(scope, "scope");
        content = normalizeContent(content);
        summary = optionalText(summary);
        sourceRunId = optionalText(sourceRunId);
        sourceEventIds = immutableTextList(sourceEventIds, "sourceEventIds");
        evidenceRefs = immutableTextList(evidenceRefs, "evidenceRefs");
        tags = immutableTextList(tags, "tags");
        requestedStatus = Objects.requireNonNull(
                requestedStatus,
                "requestedStatus"
        );
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        sourceKind = optionalText(sourceKind);
        sourceIdentity = optionalText(sourceIdentity);
        extractionPolicyVersion = optionalText(extractionPolicyVersion);

        requireScore(importance, "importance");
        requireScore(confidence, "confidence");
        if (requestedStatus != MemoryStatus.CANDIDATE
                && requestedStatus != MemoryStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "requestedStatus must be CANDIDATE or ACTIVE"
            );
        }
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException(
                    "validUntil must not be before validFrom"
            );
        }
        validateProvenance(
                sourceRunId,
                sourceEventIds,
                sourceKind,
                sourceIdentity,
                extractionPolicyVersion
        );
    }

    public boolean automaticSource() {
        return sourceKind != null;
    }

    static String normalizeContent(String value) {
        return requireText(value, "content").replaceAll("\\s+", " ");
    }

    private static void validateProvenance(
            String sourceRunId,
            List<String> sourceEventIds,
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion
    ) {
        boolean automatic = sourceRunId != null
                || !sourceEventIds.isEmpty()
                || sourceKind != null
                || sourceIdentity != null
                || extractionPolicyVersion != null;
        if (!automatic) {
            return;
        }
        if (sourceKind == null) {
            throw new IllegalArgumentException(
                    "automatic memory requires sourceKind"
            );
        }
        if (sourceIdentity == null) {
            throw new IllegalArgumentException(
                    "automatic memory requires sourceIdentity"
            );
        }
        if (extractionPolicyVersion == null) {
            throw new IllegalArgumentException(
                    "automatic memory requires extractionPolicyVersion"
            );
        }
        if (sourceRunId == null && sourceEventIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "automatic memory requires sourceRunId or sourceEventIds"
            );
        }
    }

    private static List<String> immutableTextList(
            List<String> values,
            String field
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> requireText(value, field + " entry"))
                .toList();
    }

    private static void requireScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    field + " must be between 0 and 1"
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
