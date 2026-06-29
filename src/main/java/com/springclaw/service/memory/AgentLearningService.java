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
            "candidate",
            "disabled",
            "rejected",
            "superseded"
    );
    private static final Pattern LEARNING_STATUS = Pattern.compile("(?im)^-\\s*status:\\s*(\\S+)\\s*$");
    private static final Pattern LEARNING_HEADING = Pattern.compile("(?m)^##\\s+.*$");

    private final boolean enabled;
    private final boolean traceFailureEnabled;
    private final Path rootPath;
    private final int maxFieldChars;

    public AgentLearningService(@Value("${springclaw.learning.enabled:true}") boolean enabled,
                                @Value("${springclaw.learning.trace-failure-enabled:true}") boolean traceFailureEnabled,
                                @Value("${springclaw.learning.root:${user.dir}/data/memory-bank}") String root,
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

    public List<AgentLearningReviewItem> listEntries(int limit) {
        if (!enabled) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try {
            Path file = rootPath.resolve(LEARNING_FILE);
            if (!Files.exists(file)) {
                return List.of();
            }
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            List<AgentLearningReviewItem> entries = new ArrayList<>();
            for (LearningSection section : learningSections(existing)) {
                String signature = sectionField(section.text(), "signature");
                if (!StringUtils.hasText(signature)) {
                    continue;
                }
                String source = sectionField(section.text(), "source");
                String trigger = sectionField(section.text(), "trigger");
                String lesson = sectionField(section.text(), "lesson");
                String rule = sectionField(section.text(), "rule");
                String counterexample = sectionField(section.text(), "counterexample");
                String evidence = sectionField(section.text(), "evidence");
                String status = extractStatus(section.text()).orElse("active");
                boolean contextIncluded = isContextIncludedStatus(status);
                entries.add(new AgentLearningReviewItem(
                        signature,
                        status,
                        source,
                        trigger,
                        lesson,
                        rule,
                        counterexample,
                        classifyCounterexample(source, trigger, counterexample, evidence),
                        contextIncluded,
                        contextIncluded ? "included_in_context" : "filtered_from_context",
                        sectionField(section.text(), "reviewedAt"),
                        sectionField(section.text(), "requestId"),
                        evidence,
                        sectionField(section.text(), "reviewReason"),
                        sectionTitle(section.text())
                ));
                if (entries.size() >= safeLimit) {
                    break;
                }
            }
            return List.copyOf(entries);
        } catch (IOException ex) {
            return List.of();
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
                - status: candidate
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
        String target = "- signature: " + signature;
        for (LearningSection section : learningSections(text)) {
            if (section.text().contains(target)) {
                return Optional.of(section);
            }
        }
        return Optional.empty();
    }

    private List<LearningSection> learningSections(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<LearningSection> sections = new ArrayList<>();
        Matcher matcher = LEARNING_HEADING.matcher(text);
        int sectionStart = -1;
        while (matcher.find()) {
            if (sectionStart >= 0) {
                sections.add(new LearningSection(sectionStart, matcher.start(), text.substring(sectionStart, matcher.start())));
            }
            sectionStart = matcher.start();
        }
        if (sectionStart >= 0) {
            sections.add(new LearningSection(sectionStart, text.length(), text.substring(sectionStart)));
        }
        return sections;
    }

    private Optional<String> extractStatus(String section) {
        Matcher matcher = LEARNING_STATUS.matcher(TextUtils.safe(section));
        return matcher.find()
                ? Optional.of(TextUtils.normalize(matcher.group(1)))
                : Optional.empty();
    }

    private String sectionField(String section, String name) {
        String prefix = "- " + name + ":";
        for (String line : TextUtils.safe(section).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String sectionTitle(String section) {
        for (String line : TextUtils.safe(section).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                return trimmed.substring(3).trim();
            }
        }
        return "";
    }

    private String classifyCounterexample(String source, String trigger, String counterexample, String evidence) {
        String text = TextUtils.normalizeWS(String.join(" ", source, trigger, counterexample, evidence)).toLowerCase(Locale.ROOT);
        if (containsAny(text,
                "workspace guard",
                "guard denied",
                "denied",
                "permission",
                "forbidden",
                "unauthorized",
                "../",
                "outside",
                "越界",
                "拒绝",
                "权限")) {
            return "permission_boundary";
        }
        if (containsAny(text,
                "too broad",
                "overgeneral",
                "过宽",
                "误伤",
                "泛化")) {
            return "overgeneralized_rule";
        }
        if (containsAny(text,
                "evidence",
                "unknown",
                "not enough",
                "no evidence",
                "证据",
                "不足",
                "缺少")) {
            return "evidence_gap";
        }
        if (containsAny(text,
                "model",
                "context",
                "memory",
                "prompt",
                "模型",
                "上下文",
                "记忆")) {
            return "model_or_context";
        }
        if (containsAny(text,
                "tool",
                "command",
                "shell",
                "script",
                "exit",
                "timeout",
                "failed",
                "工具",
                "命令",
                "脚本",
                "超时",
                "失败")) {
            return "tool_failure";
        }
        return "unknown";
    }

    private boolean isContextIncludedStatus(String status) {
        String normalized = TextUtils.normalize(status);
        return normalized.isBlank()
                || normalized.equals("active")
                || normalized.equals("approved");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
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

    public record AgentLearningReviewItem(String signature,
                                          String status,
                                          String source,
                                          String trigger,
                                          String lesson,
                                          String rule,
                                          String counterexample,
                                          String counterexampleCategory,
                                          boolean contextIncluded,
                                          String contextImpact,
                                          String reviewedAt,
                                          String requestId,
                                          String evidence,
                                          String reviewReason,
                                          String sectionTitle) {
    }

    private record LearningSection(int start, int end, String text) {
    }
}
