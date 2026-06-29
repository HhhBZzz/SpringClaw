package com.springclaw.service.memory.consolidation;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.memory.MemoryWriteCommand;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
public class MemoryConsolidationProposer {

    private static final String SOURCE_KIND = "CONSOLIDATION";
    private static final String POLICY_VERSION = "consolidation-l3-v1";

    private final Clock clock;

    public MemoryConsolidationProposer() {
        this(Clock.systemUTC());
    }

    MemoryConsolidationProposer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<MemoryConsolidationProposal> propose(
            List<MemoryRecordVersion> episodes
    ) {
        List<MemoryRecordVersion> eligible = eligibleEpisodes(episodes);
        if (eligible.size() < 2) {
            return Optional.empty();
        }
        ConsolidationKind kind = classify(eligible);
        if (kind == null) {
            return Optional.empty();
        }

        List<String> sourceVersionIds = eligible.stream()
                .map(MemoryRecordVersion::memoryVersionId)
                .distinct()
                .toList();
        List<String> evidenceRefs = eligible.stream()
                .flatMap(record -> record.evidenceRefs().stream())
                .distinct()
                .toList();
        List<String> sourceEventIds = eligible.stream()
                .flatMap(record -> record.sourceEventIds().stream())
                .distinct()
                .toList();
        List<String> tags = tagsFor(kind, eligible);
        MemoryScope scope = scopeFor(eligible.get(0));
        String content = contentFor(kind, eligible);
        String logicalId = "consolidation:" + sha256(kind + ":" + String.join(",", sourceVersionIds));
        MemoryWriteCommand command = new MemoryWriteCommand(
                logicalId,
                kind.memoryType,
                scope,
                content,
                content,
                firstRunId(eligible),
                sourceEventIds,
                evidenceRefs,
                tags,
                0.8,
                0.8,
                MemoryStatus.CANDIDATE,
                clock.instant(),
                null,
                SOURCE_KIND,
                "episodes:" + String.join("+", sourceVersionIds),
                POLICY_VERSION
        );
        return Optional.of(new MemoryConsolidationProposal(command, sourceVersionIds));
    }

    private static List<MemoryRecordVersion> eligibleEpisodes(
            List<MemoryRecordVersion> episodes
    ) {
        if (episodes == null) {
            return List.of();
        }
        return episodes.stream()
                .filter(Objects::nonNull)
                .filter(record -> record.memoryType() == MemoryType.EPISODIC)
                .filter(record -> record.status() == MemoryStatus.ACTIVE)
                .filter(record -> !record.deleted())
                .toList();
    }

    private static ConsolidationKind classify(List<MemoryRecordVersion> episodes) {
        boolean allPreference = episodes.stream()
                .allMatch(record -> containsTag(record, "preference"));
        if (allPreference) {
            return ConsolidationKind.PREFERENCE;
        }
        boolean allWorkflow = episodes.stream()
                .allMatch(record -> containsTag(record, "workflow"));
        if (allWorkflow) {
            return ConsolidationKind.WORKFLOW;
        }
        return null;
    }

    private static boolean containsTag(MemoryRecordVersion record, String tag) {
        return record.tags().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(tag::equals);
    }

    private static String contentFor(
            ConsolidationKind kind,
            List<MemoryRecordVersion> episodes
    ) {
        String combined = episodes.stream()
                .map(MemoryRecordVersion::content)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);
        if (kind == ConsolidationKind.PREFERENCE
                && containsAny(combined, "chinese", "中文")
                && containsAny(combined, "short", "concise", "brief", "简短")
                && containsAny(combined, "summary", "summaries", "progress", "进展")) {
            return "User prefers short Chinese progress summaries.";
        }
        if (kind == ConsolidationKind.WORKFLOW
                && containsAny(combined, "pr", "pull request", "mergeability")) {
            return "For PR review workflows, verify PR state and mergeability before handoff or follow-up work.";
        }
        String first = episodes.get(0).summary() == null
                ? episodes.get(0).content()
                : episodes.get(0).summary();
        if (kind == ConsolidationKind.PREFERENCE) {
            return "User has a repeated preference: " + abbreviate(first);
        }
        return "User has a repeated workflow pattern: " + abbreviate(first);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tagsFor(
            ConsolidationKind kind,
            List<MemoryRecordVersion> episodes
    ) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("consolidated");
        tags.add(kind == ConsolidationKind.PREFERENCE ? "preference" : "workflow");
        for (MemoryRecordVersion episode : episodes) {
            tags.addAll(episode.tags());
        }
        return List.copyOf(tags);
    }

    private static MemoryScope scopeFor(MemoryRecordVersion record) {
        String owner = record.ownerUserId() == null
                ? record.scopeId()
                : record.ownerUserId();
        return switch (record.scopeType()) {
            case USER -> MemoryScope.user("consolidation", "l3", record.scopeId());
            case PERSONAL_SESSION, SHARED_SESSION -> MemoryScope.user("consolidation", "l3", owner);
            case PROJECT -> MemoryScope.user("consolidation", "l3", owner);
        };
    }

    private static String firstRunId(List<MemoryRecordVersion> episodes) {
        return episodes.stream()
                .map(MemoryRecordVersion::sourceRunId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("consolidation");
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "repeated episode evidence.";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 177) + "...";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private enum ConsolidationKind {
        PREFERENCE(MemoryType.SEMANTIC),
        WORKFLOW(MemoryType.PROCEDURAL);

        private final MemoryType memoryType;

        ConsolidationKind(MemoryType memoryType) {
            this.memoryType = memoryType;
        }
    }
}
