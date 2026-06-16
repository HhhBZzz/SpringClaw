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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts execution failures into reviewable Memory Bank learning entries.
 */
@Service
public class AgentLearningService {

    public static final String LEARNING_SCHEMA = "springclaw.agent-learning.v1";
    private static final String LEARNING_FILE = "agent-learnings.md";
    private static final Set<String> REVIEWABLE_STATUSES = Set.of(
            "active",
            "approved",
            "disabled",
            "rejected",
            "superseded"
    );
    private static final Pattern LEARNING_STATUS = Pattern.compile("(?im)^-\\s*status:\\s*(\\S+)\\s*$");

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

    public Optional<AgentLearningStatusUpdate> updateStatus(String signature, String status, String reason) {
        String normalizedSignature = TextUtils.normalizeWS(signature);
        String normalizedStatus = TextUtils.normalize(status);
        if (!enabled
                || !StringUtils.hasText(normalizedSignature)
                || !REVIEWABLE_STATUSES.contains(normalizedStatus)) {
            return Optional.empty();
        }
        try {
            Path file = rootPath.resolve(LEARNING_FILE);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            LearningSection section = findSection(existing, normalizedSignature).orElse(null);
            if (section == null) {
                return Optional.empty();
            }
            String previousStatus = extractStatus(section.text()).orElse("active");
            String updatedSection = updateSectionStatus(section.text(), normalizedStatus, reason);
            String updated = existing.substring(0, section.start())
                    + updatedSection
                    + existing.substring(section.end());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return Optional.of(new AgentLearningStatusUpdate(
                    normalizedSignature,
                    previousStatus,
                    normalizedStatus,
                    field(reason)
            ));
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

    private Optional<LearningSection> findSection(String text, String signature) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        int signatureIndex = text.indexOf("- signature: " + signature);
        if (signatureIndex < 0) {
            return Optional.empty();
        }
        int start = text.lastIndexOf("\n## ", signatureIndex);
        if (start >= 0) {
            start += 1;
        } else if (text.startsWith("## ")) {
            start = 0;
        } else {
            return Optional.empty();
        }
        int end = text.indexOf("\n## ", signatureIndex);
        if (end < 0) {
            end = text.length();
        }
        return Optional.of(new LearningSection(start, end, text.substring(start, end)));
    }

    private Optional<String> extractStatus(String section) {
        Matcher matcher = LEARNING_STATUS.matcher(TextUtils.safe(section));
        return matcher.find()
                ? Optional.of(TextUtils.normalize(matcher.group(1)))
                : Optional.empty();
    }

    private String updateSectionStatus(String section, String status, String reason) {
        List<String> lines = new ArrayList<>(List.of(section.split("\\R", -1)));
        int statusIndex = firstLineIndex(lines, "- status:");
        if (statusIndex >= 0) {
            lines.set(statusIndex, "- status: " + status);
        } else {
            int schemaIndex = firstLineIndex(lines, "- schema:");
            lines.add(schemaIndex >= 0 ? schemaIndex + 1 : Math.min(2, lines.size()), "- status: " + status);
        }

        replaceOrAppendReviewLine(lines, "- reviewedAt:", "- reviewedAt: " + Instant.now());
        String reviewReason = field(reason);
        if (StringUtils.hasText(reviewReason)) {
            replaceOrAppendReviewLine(lines, "- reviewReason:", "- reviewReason: " + reviewReason);
        }
        return String.join("\n", lines);
    }

    private int firstLineIndex(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (TextUtils.safe(lines.get(i)).trim().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private void replaceOrAppendReviewLine(List<String> lines, String prefix, String value) {
        int existingIndex = firstLineIndex(lines, prefix);
        if (existingIndex >= 0) {
            lines.set(existingIndex, value);
            return;
        }
        int signatureIndex = firstLineIndex(lines, "- signature:");
        lines.add(signatureIndex >= 0 ? signatureIndex : Math.max(0, lines.size() - 1), value);
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

    public record AgentLearningStatusUpdate(String signature,
                                            String previousStatus,
                                            String status,
                                            String reason) {
    }

    private record LearningSection(int start, int end, String text) {
    }
}
