package com.springclaw.service.memory;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.agent.AgentRunTraceEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Converts execution failures into reviewable Memory Bank learning entries.
 */
@Service
public class AgentLearningService {

    public static final String LEARNING_SCHEMA = "springclaw.agent-learning.v1";
    private static final String LEARNING_FILE = "agent-learnings.md";

    private final boolean enabled;
    private final boolean traceFailureEnabled;
    private final Path rootPath;
    private final int maxFieldChars;

    public AgentLearningService(@Value("${springclaw.learning.enabled:true}") boolean enabled,
                                @Value("${springclaw.learning.trace-failure-enabled:true}") boolean traceFailureEnabled,
                                @Value("${springclaw.learning.root:${user.dir}/docs/memory-bank}") String root,
                                @Value("${springclaw.learning.max-field-chars:600}") int maxFieldChars) {
        this.enabled = enabled;
        this.traceFailureEnabled = traceFailureEnabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxFieldChars = Math.max(120, maxFieldChars);
    }

    public Optional<AgentLearningEntry> captureTraceFailure(AgentRunTraceEvent event) {
        if (!enabled || !traceFailureEnabled || event == null || !isFailureStatus(event.status())) {
            return Optional.empty();
        }
        String trigger = joinParts(event.category(), event.action(), event.target());
        if (!StringUtils.hasText(trigger)) {
            trigger = TextUtils.safe(event.stepName(), "agent-step");
        }
        return capture(new AgentLearningCandidate(
                event.requestId(),
                "trace-failure",
                trigger,
                "执行步骤失败时，先复盘 trace detail 中的拒绝、错误或证据缺口，再决定是否重试或换路径。",
                "不要在未改变输入、权限或上下文条件时重复执行同一失败动作。",
                event.detail(),
                "status=" + TextUtils.safe(event.status())
                        + ", type=" + TextUtils.safe(event.type())
                        + ", riskLevel=" + TextUtils.safe(event.riskLevel())
        ));
    }

    public Optional<AgentLearningEntry> capture(AgentLearningCandidate candidate) {
        if (!enabled || candidate == null) {
            return Optional.empty();
        }
        AgentLearningEntry entry = normalize(candidate);
        if (!entry.valid()) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(rootPath);
            Path file = rootPath.resolve(LEARNING_FILE);
            String existing = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (existing.contains("signature: " + entry.signature())) {
                return Optional.empty();
            }
            String updated = existing.isBlank()
                    ? renderHeader() + renderEntry(entry)
                    : existing.stripTrailing() + "\n\n" + renderEntry(entry);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return Optional.of(entry);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private AgentLearningEntry normalize(AgentLearningCandidate candidate) {
        String requestId = field(candidate.requestId());
        String source = field(TextUtils.safe(candidate.source(), "manual"));
        String trigger = field(candidate.trigger());
        String lesson = field(candidate.lesson());
        String rule = field(candidate.rule());
        String counterexample = field(candidate.counterexample());
        String evidence = field(candidate.evidence());
        String signature = signature(source + "\n" + trigger + "\n" + rule + "\n" + counterexample);
        return new AgentLearningEntry(
                requestId,
                source,
                trigger,
                lesson,
                rule,
                counterexample,
                evidence,
                signature
        );
    }

    private boolean isFailureStatus(String status) {
        String value = TextUtils.normalize(status);
        return value.equals("failed")
                || value.equals("failure")
                || value.equals("error")
                || value.equals("denied")
                || value.equals("rejected");
    }

    private String field(String value) {
        String normalized = TextUtils.normalizeWS(value).replace("|", "\\|");
        return TextUtils.truncate(normalized, maxFieldChars).replace("\n", " ");
    }

    private String renderHeader() {
        return """
                # Agent Learnings

                <!-- schema: %s -->

                """.formatted(LEARNING_SCHEMA);
    }

    private String renderEntry(AgentLearningEntry entry) {
        return """
                ## %s

                - schema: %s
                - status: active
                - requestId: %s
                - source: %s
                - trigger: %s
                - lesson: %s
                - rule: %s
                - counterexample: %s
                - evidence: %s
                - signature: %s
                """.formatted(
                Instant.now(),
                LEARNING_SCHEMA,
                entry.requestId(),
                entry.source(),
                entry.trigger(),
                entry.lesson(),
                entry.rule(),
                entry.counterexample(),
                entry.evidence(),
                entry.signature()
        ).trim();
    }

    private String joinParts(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(".");
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private String signature(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(TextUtils.safe(value).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (Exception ex) {
            return Integer.toHexString(TextUtils.safe(value).hashCode());
        }
    }

    public record AgentLearningCandidate(String requestId,
                                         String source,
                                         String trigger,
                                         String lesson,
                                         String rule,
                                         String counterexample,
                                         String evidence) {
    }

    public record AgentLearningEntry(String requestId,
                                     String source,
                                     String trigger,
                                     String lesson,
                                     String rule,
                                     String counterexample,
                                     String evidence,
                                     String signature) {

        boolean valid() {
            return StringUtils.hasText(trigger)
                    && StringUtils.hasText(lesson)
                    && StringUtils.hasText(rule)
                    && StringUtils.hasText(counterexample);
        }
    }
}
