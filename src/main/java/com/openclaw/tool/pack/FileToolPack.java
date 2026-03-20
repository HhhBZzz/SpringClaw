package com.openclaw.tool.pack;

import com.openclaw.common.exception.BusinessException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 文件工具包。
 *
 * 设计说明：
 * 1. 仅允许访问受控根目录，避免任意文件读写风险。
 * 2. 通过 @Tool 暴露给模型，避免手写 Function Calling 协议。
 */
@Component
public class FileToolPack {

    private static final int MAX_FILE_SEARCH_RESULTS = 100;
    private static final int MAX_CONTENT_SEARCH_RESULTS = 50;

    private final Path rootPath;
    private final int maxReadChars;

    public FileToolPack(@Value("${openclaw.tools.file.root:${user.dir}}") String root,
                        @Value("${openclaw.tools.file.max-read-chars:12000}") int maxReadChars) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxReadChars = Math.max(2048, maxReadChars);
    }

    @Tool(description = "列出指定目录下的文件和目录")
    public String listFiles(String relativeDir) {
        Path dir = resolveSafePath(relativeDir, true);
        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return "目录不存在: " + relativeDir;
            }
            try (var stream = Files.list(dir)) {
                List<String> lines = stream
                        .limit(200)
                        .map(path -> (Files.isDirectory(path) ? "[D] " : "[F] ") + rootPath.relativize(path))
                        .collect(Collectors.toList());
                if (lines.isEmpty()) {
                    return "目录为空: " + relativeDir;
                }
                return String.join("\n", lines);
            }
        } catch (IOException ex) {
            throw new BusinessException(50021, "文件工具 listFiles 失败: " + ex.getMessage());
        }
    }

    @Tool(description = "读取指定文本文件内容")
    public String readTextFile(String relativeFilePath) {
        Path file = resolveSafePath(relativeFilePath, false);
        try {
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return "文件不存在: " + relativeFilePath;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > maxReadChars) {
                return content.substring(0, maxReadChars) + "\n...<TRUNCATED>";
            }
            return content;
        } catch (IOException ex) {
            throw new BusinessException(50022, "文件工具 readTextFile 失败: " + ex.getMessage());
        }
    }

    @Tool(description = "写入文本到指定文件。默认覆盖原文件")
    public String writeTextFile(String relativeFilePath, String content, Boolean overwrite) {
        Path file = resolveSafePath(relativeFilePath, false);
        boolean canOverwrite = overwrite == null || overwrite;

        try {
            if (Files.exists(file) && !canOverwrite) {
                return "文件已存在，且 overwrite=false: " + relativeFilePath;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return "写入成功: " + rootPath.relativize(file);
        } catch (IOException ex) {
            throw new BusinessException(50023, "文件工具 writeTextFile 失败: " + ex.getMessage());
        }
    }

    @Tool(description = "按文件名递归搜索文件，支持通配符，例如 *.java、*Service*.java、application*.yml")
    public String searchFiles(String pattern) {
        String normalizedPattern = normalizePattern(pattern, "*");
        PathMatcher matcher = buildMatcher(normalizedPattern);
        List<String> results = new ArrayList<>();
        try (var stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> collectMatchedFile(results, path, matcher));
        } catch (IOException ex) {
            throw new BusinessException(50024, "文件工具 searchFiles 失败: " + ex.getMessage());
        }
        if (results.isEmpty()) {
            return "未找到匹配文件: " + normalizedPattern;
        }
        return String.join("\n", results);
    }

    @Tool(description = "按内容递归搜索文件，支持 filePattern 过滤，类似 grep。示例：keyword=ChatService, filePattern=*.java")
    public String searchInFiles(String keyword, String filePattern) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(40053, "keyword 不能为空");
        }
        String normalizedPattern = normalizePattern(filePattern, "*");
        PathMatcher matcher = buildMatcher(normalizedPattern);
        List<String> results = new ArrayList<>();
        String keywordLower = keyword.trim().toLowerCase(Locale.ROOT);
        try (var stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> matchesPattern(path, matcher))
                    .forEach(path -> grepFile(results, path, keywordLower));
        } catch (IOException ex) {
            throw new BusinessException(50025, "文件工具 searchInFiles 失败: " + ex.getMessage());
        }
        if (results.isEmpty()) {
            return "未找到关键词命中: " + keyword.trim() + "（filePattern=" + normalizedPattern + "）";
        }
        return String.join("\n", results);
    }

    private Path resolveSafePath(String raw, boolean allowBlankAsRoot) {
        String value = StringUtils.hasText(raw) ? raw.trim() : "";
        if (!StringUtils.hasText(value) && allowBlankAsRoot) {
            return rootPath;
        }
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(40051, "文件路径不能为空");
        }

        Path target = rootPath.resolve(value).normalize().toAbsolutePath();
        if (!target.startsWith(rootPath)) {
            throw new BusinessException(40052, "文件路径越界，禁止访问 root 外路径");
        }
        return target;
    }

    private String normalizePattern(String pattern, String fallback) {
        if (!StringUtils.hasText(pattern)) {
            return fallback;
        }
        return pattern.trim();
    }

    private PathMatcher buildMatcher(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private void collectMatchedFile(List<String> results, Path path, PathMatcher matcher) {
        if (results.size() >= MAX_FILE_SEARCH_RESULTS || !matchesPattern(path, matcher)) {
            return;
        }
        results.add("[F] " + rootPath.relativize(path));
    }

    private boolean matchesPattern(Path path, PathMatcher matcher) {
        Path relative = rootPath.relativize(path);
        return matcher.matches(relative) || matcher.matches(relative.getFileName());
    }

    private void grepFile(List<String> results, Path path, String keywordLower) {
        if (results.size() >= MAX_CONTENT_SEARCH_RESULTS) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && results.size() < MAX_CONTENT_SEARCH_RESULTS; i++) {
                String line = lines.get(i);
                if (line.toLowerCase(Locale.ROOT).contains(keywordLower)) {
                    results.add(rootPath.relativize(path) + ":" + (i + 1) + ": " + truncateLine(line));
                }
            }
        } catch (IOException ex) {
            // 忽略单文件读取失败，继续搜索其他文件。
        }
    }

    private String truncateLine(String line) {
        if (line == null) {
            return "";
        }
        String normalized = line.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }
}
