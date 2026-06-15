package com.springclaw.service.memory;

import com.springclaw.common.util.TextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件化项目记忆，面向非 RAG 的 harness 上下文恢复。
 */
@Service
public class MemoryBankService {

    private static final String AGENT_LEARNINGS_FILE = "agent-learnings.md";
    private static final Pattern AGENT_LEARNING_STATUS =
            Pattern.compile("(?im)^-\\s*status:\\s*(\\S+)\\s*$");

    private static final List<String> ORDERED_FILES = List.of(
            "project-brief.md",
            "current-state.md",
            "architecture-decisions.md",
            "agent-learnings.md",
            "progress.md",
            "user-preferences.md"
    );

    private final boolean enabled;
    private final Path rootPath;
    private final int maxChars;

    public MemoryBankService(@Value("${springclaw.memory.bank-enabled:true}") boolean enabled,
                             @Value("${springclaw.memory.bank-root:${user.dir}/docs/memory-bank}") String root,
                             @Value("${springclaw.memory.bank-max-chars:2400}") int maxChars) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxChars = Math.max(400, maxChars);
    }

    public String renderContext() {
        return renderSnapshot().context();
    }

    public MemoryBankSnapshot renderSnapshot() {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return MemoryBankSnapshot.empty();
        }
        StringBuilder builder = new StringBuilder();
        int activeLearningCount = 0;
        int filteredLearningCount = 0;
        for (Path file : orderedMarkdownFiles()) {
            MemoryText memoryText = appendFile(builder, file);
            activeLearningCount += memoryText.activeLearningCount();
            filteredLearningCount += memoryText.filteredLearningCount();
            if (builder.length() >= maxChars) {
                break;
            }
        }
        return new MemoryBankSnapshot(
                TextUtils.truncate(builder.toString(), maxChars).trim(),
                activeLearningCount,
                filteredLearningCount
        );
    }

    private List<Path> orderedMarkdownFiles() {
        try {
            return Files.list(rootPath)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparingInt(this::fileOrder)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private int fileOrder(Path path) {
        String name = path.getFileName().toString();
        int index = ORDERED_FILES.indexOf(name);
        return index < 0 ? ORDERED_FILES.size() : index;
    }

    private MemoryText appendFile(StringBuilder builder, Path file) {
        try {
            MemoryText memoryText = readMemoryText(file);
            String text = TextUtils.normalizeWS(memoryText.text());
            if (!StringUtils.hasText(text)) {
                return memoryText;
            }
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("### ").append(name).append("\n");
            builder.append(text).append("\n");
            return memoryText;
        } catch (IOException ignored) {
            // 单个记忆文件读取失败时跳过，避免影响主链路。
            return MemoryText.empty();
        }
    }

    private MemoryText readMemoryText(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (AGENT_LEARNINGS_FILE.equals(file.getFileName().toString())) {
            LearningFilterResult result = filterAgentLearnings(text);
            return new MemoryText(result.text(), result.activeCount(), result.filteredCount());
        }
        return new MemoryText(text, 0, 0);
    }

    private LearningFilterResult filterAgentLearnings(String text) {
        if (!StringUtils.hasText(text)) {
            return LearningFilterResult.empty();
        }
        StringBuilder preamble = new StringBuilder();
        List<String> sections = new ArrayList<>();
        StringBuilder currentSection = new StringBuilder();
        boolean inLearningSection = false;

        for (String line : text.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                if (inLearningSection) {
                    sections.add(currentSection.toString());
                }
                currentSection = new StringBuilder();
                currentSection.append(line).append('\n');
                inLearningSection = true;
                continue;
            }
            if (inLearningSection) {
                currentSection.append(line).append('\n');
            } else {
                preamble.append(line).append('\n');
            }
        }
        if (inLearningSection) {
            sections.add(currentSection.toString());
        }

        StringBuilder filtered = new StringBuilder(preamble);
        int activeCount = 0;
        int filteredCount = 0;
        for (String section : sections) {
            if (isActiveLearningSection(section)) {
                filtered.append(section);
                activeCount++;
            } else {
                filteredCount++;
            }
        }
        return new LearningFilterResult(filtered.toString(), activeCount, filteredCount);
    }

    private boolean isActiveLearningSection(String section) {
        Matcher matcher = AGENT_LEARNING_STATUS.matcher(section);
        if (!matcher.find()) {
            return true;
        }
        String status = matcher.group(1).toLowerCase(Locale.ROOT);
        return "active".equals(status) || "approved".equals(status);
    }

    public record MemoryBankSnapshot(String context,
                                     int activeLearningCount,
                                     int filteredLearningCount) {

        public MemoryBankSnapshot {
            context = TextUtils.safe(context);
            activeLearningCount = Math.max(0, activeLearningCount);
            filteredLearningCount = Math.max(0, filteredLearningCount);
        }

        public static MemoryBankSnapshot empty() {
            return new MemoryBankSnapshot("", 0, 0);
        }
    }

    private record MemoryText(String text,
                              int activeLearningCount,
                              int filteredLearningCount) {

        private static MemoryText empty() {
            return new MemoryText("", 0, 0);
        }
    }

    private record LearningFilterResult(String text,
                                        int activeCount,
                                        int filteredCount) {

        private static LearningFilterResult empty() {
            return new LearningFilterResult("", 0, 0);
        }
    }
}
