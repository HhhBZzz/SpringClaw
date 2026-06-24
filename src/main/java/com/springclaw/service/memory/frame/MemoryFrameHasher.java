package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

public final class MemoryFrameHasher {

    private MemoryFrameHasher() {
    }

    public static String hash(MemoryFrame frame) {
        Objects.requireNonNull(frame, "frame");
        StringBuilder canonical = new StringBuilder(4096);
        append(canonical, "runId", frame.runId());
        append(canonical, "scopeType", frame.scope().scopeType().name());
        append(canonical, "scopeId", frame.scope().scopeId());
        frame.workingMemoryRefs().forEach(item ->
                appendItem(canonical, "working", item));
        frame.shortTermTurns().forEach(item ->
                appendItem(canonical, "shortTerm", item));
        frame.episodicItems().forEach(item ->
                appendItem(canonical, "episodic", item));
        frame.semanticFacts().forEach(item ->
                appendItem(canonical, "semantic", item));
        frame.proceduralRules().forEach(item ->
                appendItem(canonical, "procedural", item));
        frame.projectItems().forEach(item ->
                appendItem(canonical, "project", item));
        frame.sourceSummary().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> append(
                        canonical,
                        "summary:" + entry.getKey(),
                        entry.getValue()
                ));
        frame.omissions().stream()
                .sorted(Comparator
                        .comparing((MemoryFrameOmission omission) ->
                                omission.category().name())
                        .thenComparing(omission -> omission.layer().name())
                        .thenComparing(MemoryFrameOmission::sourceId)
                        .thenComparing(MemoryFrameOmission::reason))
                .forEach(omission -> appendOmission(canonical, omission));
        return sha256(canonical.toString());
    }

    private static void appendItem(
            StringBuilder canonical,
            String listName,
            MemoryFrameItem item
    ) {
        append(canonical, listName + ".sourceId", item.sourceId());
        append(canonical, listName + ".sourceKind", item.sourceKind().name());
        append(canonical, listName + ".layer", item.layer().name());
        append(canonical, listName + ".memoryVersionId", item.memoryVersionId());
        append(canonical, listName + ".contentHash", item.contentHash());
        append(canonical, listName + ".score", Double.toString(item.score()));
        append(canonical, listName + ".version", Integer.toString(item.version()));
    }

    private static void appendOmission(
            StringBuilder canonical,
            MemoryFrameOmission omission
    ) {
        append(canonical, "omission.category", omission.category().name());
        append(canonical, "omission.layer", omission.layer().name());
        append(canonical, "omission.sourceId", omission.sourceId());
        append(canonical, "omission.reason", omission.reason());
    }

    private static void append(StringBuilder canonical, String key, String value) {
        canonical.append(key)
                .append('=')
                .append(value == null ? "" : value)
                .append('\n');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
