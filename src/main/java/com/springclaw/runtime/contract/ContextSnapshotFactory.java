package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ContextSnapshotFactory {

    private static final String SCHEMA = "springclaw.context-snapshot.v1";

    private final MemoryCoordinator memoryCoordinator;
    private final Clock clock;

    public ContextSnapshotFactory(MemoryCoordinator memoryCoordinator, Clock clock) {
        this.memoryCoordinator = Objects.requireNonNull(memoryCoordinator, "memoryCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ContextSnapshot create(ContextSnapshotRequest request) {
        Objects.requireNonNull(request, "request");
        MemoryScope scope = MemoryScope.from(request.sessionAccessClaim());
        MemoryFrameResult result = memoryCoordinator.retrieve(new MemoryFrameRequest(
                request.runId(),
                scope,
                request.effectiveMessage()
        ));
        MemoryFrame frame = result.frame();
        MemoryRetrievalTrace trace = result.trace();
        Instant capturedAt = clock.instant();
        Map<String, String> sourceSummary = sourceSummary(frame, trace);
        String hash = snapshotHash(request, frame, sourceSummary);
        return new ContextSnapshot(
                request.runId(),
                request.sessionKey(),
                request.sessionOwnerUserId(),
                request.channel(),
                request.userId(),
                request.roleCode(),
                request.originalMessage(),
                request.effectiveMessage(),
                request.systemPrompt(),
                renderProject(frame),
                frame.shortTermTurns().stream().map(item -> item.content()).toList(),
                frame.semanticFacts().stream().map(item -> item.content()).toList(),
                frame.proceduralRules().stream().map(item -> item.content()).toList(),
                request.allowedCapabilities(),
                request.providerSnapshot(),
                sourceSummary,
                frame,
                capturedAt,
                hash
        );
    }

    private static Map<String, String> sourceSummary(MemoryFrame frame, MemoryRetrievalTrace trace) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("schema", SCHEMA);
        summary.put("memoryFrameHash", frame.frameHash());
        summary.put("traceFrameHash", trace.frameHash());
        summary.put("shortTermCount", Integer.toString(frame.shortTermTurns().size()));
        summary.put("episodicCount", Integer.toString(frame.episodicItems().size()));
        summary.put("semanticCount", Integer.toString(frame.semanticFacts().size()));
        summary.put("proceduralCount", Integer.toString(frame.proceduralRules().size()));
        summary.put("projectCount", Integer.toString(frame.projectItems().size()));
        summary.put("omissionCount", Integer.toString(frame.omissions().size()));
        return Map.copyOf(summary);
    }

    private static String renderProject(MemoryFrame frame) {
        return frame.projectItems().stream()
                .map(item -> item.content())
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static String snapshotHash(ContextSnapshotRequest request, MemoryFrame frame, Map<String, String> summary) {
        return sha256(String.join("\n",
                request.runId(),
                request.sessionKey(),
                request.channel(),
                request.userId(),
                request.roleCode(),
                request.originalMessage(),
                request.effectiveMessage(),
                request.systemPrompt(),
                request.allowedCapabilities().toString(),
                request.providerSnapshot().toString(),
                summary.toString(),
                frame.frameHash()
        ));
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
