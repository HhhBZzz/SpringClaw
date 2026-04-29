package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.workspace.WorkspaceTaskService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工作区检索工具包。
 *
 * 设计说明：
 * 1. 面向“不知道路径”的场景，先找文件再读内容，降低使用门槛。
 * 2. 默认跳过 .git/target 等目录，避免无效扫描和噪音结果。
 */
@Component
public class WorkspaceSearchToolPack {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "yml", "yaml", "md", "txt", "json", "sql",
            "properties", "js", "ts", "tsx", "jsx", "html", "css", "sh", "py"
    );

    private final Path rootPath;
    private final int maxDepth;
    private final int maxCandidates;
    private final int maxHits;
    private final int maxResultChars;
    private final long maxFileSizeBytes;
    private final WorkspaceTaskService workspaceTaskService;

    public WorkspaceSearchToolPack(
            WorkspaceTaskService workspaceTaskService,
            @Value("${springclaw.tools.file.root:${user.dir}}") String root,
            @Value("${springclaw.tools.workspace.max-depth:8}") int maxDepth,
            @Value("${springclaw.tools.workspace.max-candidates:4000}") int maxCandidates,
            @Value("${springclaw.tools.workspace.max-hits:30}") int maxHits,
            @Value("${springclaw.tools.workspace.max-result-chars:5000}") int maxResultChars,
            @Value("${springclaw.tools.workspace.max-file-size-kb:512}") int maxFileSizeKb) {
        this.workspaceTaskService = workspaceTaskService;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxDepth = Math.max(2, maxDepth);
        this.maxCandidates = Math.max(200, maxCandidates);
        this.maxHits = Math.max(5, maxHits);
        this.maxResultChars = Math.max(1000, maxResultChars);
        this.maxFileSizeBytes = Math.max(64, maxFileSizeKb) * 1024L;
    }

    @Tool(description = "按文件名关键词检索项目文件路径，适用于不知道具体路径时快速定位")
    public String findFilesByName(String keyword) {
        String key = normalizeKeyword(keyword);
        List<String> lines = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(rootPath, maxDepth)) {
            stream.filter(path -> !path.equals(rootPath))
                    .filter(path -> !isIgnored(path))
                    .filter(path -> fileNameContains(path, key))
                    .limit(maxHits)
                    .forEach(path -> lines.add((Files.isDirectory(path) ? "[D] " : "[F] ") + toRelative(path)));
        } catch (IOException ex) {
            throw new BusinessException(50041, "工作区文件名检索失败: " + ex.getMessage());
        }

        if (lines.isEmpty()) {
            return "未找到匹配文件名: " + key;
        }
        return trimToMaxChars(String.join("\n", lines));
    }

    @Tool(description = "按关键词检索项目文本内容并返回命中行（无需提前知道文件路径）")
    public String grepProjectText(String keyword) {
        String key = normalizeKeyword(keyword);
        String lowerKey = key.toLowerCase(Locale.ROOT);

        List<String> hits = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> stream = Files.walk(rootPath, maxDepth)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(path))
                    .filter(this::isLikelyTextFile)
                    .limit(maxCandidates)
                    .toList();

            for (Path file : files) {
                scanned++;
                if (hits.size() >= maxHits) {
                    break;
                }
                scanFileForHits(file, lowerKey, hits);
            }
        } catch (IOException ex) {
            throw new BusinessException(50042, "工作区内容检索失败: " + ex.getMessage());
        }

        if (hits.isEmpty()) {
            return "未找到关键词命中: " + key + "（已扫描文件数=" + scanned + "）";
        }
        return trimToMaxChars("命中结果（关键词=" + key + "）\n" + String.join("\n", hits));
    }

    @Tool(description = "按文件名关键词找到第一个匹配文件并读取内容，适用于先定位再阅读")
    public String readFirstMatchedFile(String fileNameKeyword) {
        String key = normalizeKeyword(fileNameKeyword);
        Path target = null;

        try (Stream<Path> stream = Files.walk(rootPath, maxDepth)) {
            target = stream.filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(path))
                    .filter(path -> fileNameContains(path, key))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            throw new BusinessException(50043, "匹配文件读取失败: " + ex.getMessage());
        }

        if (target == null) {
            return "未找到匹配文件: " + key;
        }
        if (!isLikelyTextFile(target)) {
            return "匹配到的文件不是可读文本: " + toRelative(target);
        }

        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return "FILE: " + toRelative(target) + "\n" + trimToMaxChars(content);
        } catch (IOException ex) {
            throw new BusinessException(50044, "读取匹配文件失败: " + ex.getMessage());
        }
    }

    @Tool(description = "按任务目标自动分析工作区，输出相关文件和关键代码片段，适用于“这个功能在哪实现”之类的问题")
    public String analyzeWorkspaceTask(String goal) {
        return workspaceTaskService.analyzeTask(goal);
    }

    private void scanFileForHits(Path file, String lowerKeyword, List<String> hits) {
        try {
            if (Files.size(file) > maxFileSizeBytes) {
                return;
            }
        } catch (IOException ignore) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null && hits.size() < maxHits) {
                lineNo++;
                String normalized = line.toLowerCase(Locale.ROOT);
                if (normalized.contains(lowerKeyword)) {
                    hits.add(toRelative(file) + ":" + lineNo + ": " + compactLine(line));
                }
            }
        } catch (Exception ignore) {
            // 跳过不可读文本文件，不中断整体检索
        }
    }

    private boolean isLikelyTextFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return name.equalsIgnoreCase("Dockerfile") || name.equalsIgnoreCase("Jenkinsfile");
        }
        String ext = name.substring(idx + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }

    private boolean fileNameContains(Path path, String keyword) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains(keyword.toLowerCase(Locale.ROOT));
    }

    private boolean isIgnored(Path path) {
        String relative = toRelative(path).toLowerCase(Locale.ROOT);
        return relative.startsWith(".git/")
                || relative.startsWith("target/")
                || relative.startsWith("node_modules/")
                || relative.contains("/.git/")
                || relative.contains("/target/")
                || relative.contains("/node_modules/");
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(40071, "检索关键词不能为空");
        }
        String value = keyword.trim();
        if (value.length() < 2) {
            throw new BusinessException(40072, "检索关键词至少 2 个字符");
        }
        if (value.length() > 80) {
            throw new BusinessException(40073, "检索关键词过长");
        }
        return value;
    }

    private String compactLine(String line) {
        if (line == null) {
            return "";
        }
        String compact = line.replaceAll("\\s+", " ").trim();
        if (compact.length() > 140) {
            return compact.substring(0, 140) + "...";
        }
        return compact;
    }

    private String toRelative(Path path) {
        try {
            return rootPath.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
        } catch (Exception ex) {
            return path.toString().replace("\\", "/");
        }
    }

    private String trimToMaxChars(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxResultChars) {
            return text;
        }
        return text.substring(0, maxResultChars) + "\n...<TRUNCATED>";
    }
}
