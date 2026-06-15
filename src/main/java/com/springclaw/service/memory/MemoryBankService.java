package com.springclaw.service.memory;

import com.springclaw.common.util.TextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 文件化项目记忆，面向非 RAG 的 harness 上下文恢复。
 */
@Service
public class MemoryBankService {

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
        if (!enabled || !Files.isDirectory(rootPath)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Path file : orderedMarkdownFiles()) {
            appendFile(builder, file);
            if (builder.length() >= maxChars) {
                break;
            }
        }
        return TextUtils.truncate(builder.toString(), maxChars).trim();
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

    private void appendFile(StringBuilder builder, Path file) {
        try {
            String text = TextUtils.normalizeWS(Files.readString(file, StandardCharsets.UTF_8));
            if (!StringUtils.hasText(text)) {
                return;
            }
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("### ").append(name).append("\n");
            builder.append(text).append("\n");
        } catch (IOException ignored) {
            // 单个记忆文件读取失败时跳过，避免影响主链路。
        }
    }
}
