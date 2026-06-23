package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryRecordVersion(
        Long recordId,
        String logicalMemoryId,
        String memoryVersionId,
        MemoryType memoryType,
        MemoryScopeType scopeType,
        String scopeId,
        String ownerUserId,
        String content,
        String contentHash,
        String summary,
        String sourceRunId,
        List<String> sourceEventIds,
        List<String> evidenceRefs,
        List<String> tags,
        double importance,
        double confidence,
        MemoryStatus status,
        Instant validFrom,
        Instant validUntil,
        Long supersedesRecordId,
        int version,
        Integer activeSlot,
        String sourceKind,
        String sourceIdentity,
        String extractionPolicyVersion,
        long indexRevision,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
    public MemoryRecordVersion {
        if (recordId != null && recordId <= 0) {
            throw new IllegalArgumentException("recordId must be positive");
        }
        logicalMemoryId = requireText(logicalMemoryId, "logicalMemoryId");
        memoryVersionId = requireText(memoryVersionId, "memoryVersionId");
        memoryType = Objects.requireNonNull(memoryType, "memoryType");
        scopeType = Objects.requireNonNull(scopeType, "scopeType");
        scopeId = requireText(scopeId, "scopeId");
        ownerUserId = optionalText(ownerUserId);
        content = requireText(content, "content");
        contentHash = requireText(contentHash, "contentHash");
        summary = optionalText(summary);
        sourceRunId = optionalText(sourceRunId);
        sourceEventIds = immutableTextList(sourceEventIds, "sourceEventIds");
        evidenceRefs = immutableTextList(evidenceRefs, "evidenceRefs");
        tags = immutableTextList(tags, "tags");
        status = Objects.requireNonNull(status, "status");
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        sourceKind = optionalText(sourceKind);
        sourceIdentity = optionalText(sourceIdentity);
        extractionPolicyVersion = optionalText(extractionPolicyVersion);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");

        requirePositive(version, "version");
        requirePositive(indexRevision, "indexRevision");
        requireScore(importance, "importance");
        requireScore(confidence, "confidence");
        if (status == MemoryStatus.ACTIVE && !Integer.valueOf(1).equals(activeSlot)) {
            throw new IllegalArgumentException("ACTIVE requires activeSlot=1");
        }
        if (status != MemoryStatus.ACTIVE && activeSlot != null) {
            throw new IllegalArgumentException(
                    "non-ACTIVE memory requires null activeSlot"
            );
        }
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException(
                    "validUntil must not be before validFrom"
            );
        }
        validateOwner(scopeType, scopeId, ownerUserId);
        validateProvenance(
                sourceRunId,
                sourceEventIds,
                sourceKind,
                sourceIdentity,
                extractionPolicyVersion
        );
    }

    private static void validateOwner(
            MemoryScopeType scopeType,
            String scopeId,
            String ownerUserId
    ) {
        if (scopeType == MemoryScopeType.PERSONAL_SESSION) {
            if (ownerUserId == null
                    || !scopeId.endsWith(":" + ownerUserId)) {
                throw new IllegalArgumentException(
                        "ownerUserId must match personal scopeId"
                );
            }
        } else if (scopeType == MemoryScopeType.USER
                && (ownerUserId == null || !scopeId.equals(ownerUserId))) {
            throw new IllegalArgumentException(
                    "ownerUserId must match user scopeId"
            );
        }
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

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
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
