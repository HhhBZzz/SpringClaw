package com.springclaw.service.memory;

import com.springclaw.common.util.TextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件化运行时记忆，面向非 RAG 的 harness 上下文恢复。
 *
 * <p>默认根目录在 data/memory-bank，避免把运行时学习数据写回 docs/ 并
 * 变成新的状态快照文档。
 */
@Service
public class MemoryBankService {

    private static final Pattern AGENT_LEARNING_STATUS =
            Pattern.compile("(?im)^-\\s*status:\\s*(\\S+)\\s*$");

    private final boolean enabled;
    private final Path rootPath;
    private final int maxChars;
    private final MarkdownProjectMemorySource projectMemorySource;

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryBankService(@Value("${springclaw.memory.bank-enabled:true}") boolean enabled,
                             @Value("${springclaw.memory.bank-root:${user.dir}/data/memory-bank}") String root,
                             @Value("${springclaw.memory.bank-max-chars:2400}") int maxChars) {
        this(enabled, root, maxChars, new MarkdownProjectMemorySource(root));
    }

    /** 测试/显式注入构造器：复用同一 rootPath 的 typed source。 */
    public MemoryBankService(boolean enabled, String root, int maxChars,
                             MarkdownProjectMemorySource projectMemorySource) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxChars = Math.max(400, maxChars);
        this.projectMemorySource = projectMemorySource;
    }

    public String renderContext() {
        return renderSnapshot().context();
    }

    public MemoryBankSnapshot renderSnapshot() {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return MemoryBankSnapshot.empty();
        }
        List<com.springclaw.runtime.memory.contract.ProjectMemoryItem> items =
                projectMemorySource.read(null);
        StringBuilder builder = new StringBuilder();
        int activeLearningCount = 0;
        int filteredLearningCount = 0;
        for (com.springclaw.runtime.memory.contract.ProjectMemoryItem item : items) {
            MemoryText memoryText = appendItem(builder, item);
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

    private MemoryText appendItem(StringBuilder builder,
                                  com.springclaw.runtime.memory.contract.ProjectMemoryItem item) {
        String text;
        int activeCount = 0;
        int filteredCount = 0;
        if (item.sourceType() == com.springclaw.runtime.memory.contract.ProjectMemoryItem.SourceType.APPROVED_LEARNING) {
            LearningFilterResult result = filterAgentLearnings(item.content());
            text = result.text();
            activeCount = result.activeCount();
            filteredCount = result.filteredCount();
        } else {
            text = item.content();
        }
        String normalized = TextUtils.normalizeWS(text);
        if (!StringUtils.hasText(normalized)) {
            return new MemoryText(text, activeCount, filteredCount);
        }
        String name = item.sourcePath().replaceFirst("\\.md$", "");
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append("### ").append(name).append("\n");
        builder.append(normalized).append("\n");
        return new MemoryText(text, activeCount, filteredCount);
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
