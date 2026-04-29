package com.springclaw.service.workspace;

import com.springclaw.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 工作区任务服务。
 *
 * 设计说明：
 * 1. 把“找文件 -> 读文件 -> 抽取片段”串成一条任务链，提升项目检索的可用性。
 * 2. 保持实现简单，适合校招项目讲解，同时比单纯 grep 更像 Agent 在做事。
 */
@Service
public class WorkspaceTaskService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}|[\\u4e00-\\u9fa5]{2,12}");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "yml", "yaml", "md", "txt", "json", "sql",
            "properties", "js", "ts", "tsx", "jsx", "html", "css", "sh", "py"
    );

    private final Path rootPath;
    private final int maxDepth;
    private final int maxCandidates;
    private final int maxSnippets;
    private final int maxReadChars;
    private final long maxFileSizeBytes;

    public WorkspaceTaskService(
            @Value("${springclaw.tools.file.root:${user.dir}}") String root,
            @Value("${springclaw.tools.workspace.max-depth:8}") int maxDepth,
            @Value("${springclaw.tools.workspace.task-max-candidates:6}") int maxCandidates,
            @Value("${springclaw.tools.workspace.task-max-snippets:8}") int maxSnippets,
            @Value("${springclaw.tools.workspace.task-max-read-chars:1200}") int maxReadChars,
            @Value("${springclaw.tools.workspace.max-file-size-kb:512}") int maxFileSizeKb) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxDepth = Math.max(2, maxDepth);
        this.maxCandidates = Math.max(1, Math.min(maxCandidates, 10));
        this.maxSnippets = Math.max(1, Math.min(maxSnippets, 12));
        this.maxReadChars = Math.max(400, maxReadChars);
        this.maxFileSizeBytes = Math.max(64, maxFileSizeKb) * 1024L;
    }

    public String analyzeTask(String goal) {
        if (!StringUtils.hasText(goal)) {
            throw new BusinessException(40074, "工作区任务不能为空");
        }

        List<String> keywords = extractKeywords(goal);
        if (keywords.isEmpty()) {
            keywords = List.of(goal.trim());
        }

        List<CandidateFile> ranked = rankCandidateFiles(keywords);
        if (ranked.isEmpty()) {
            return "未在工作区内找到和任务相关的文件。task=" + goal.trim();
        }

        StringBuilder builder = new StringBuilder();
        builder.append("WORKSPACE_TASK\n")
                .append("任务: ").append(goal.trim()).append("\n\n")
                .append("推测最相关文件:\n");

        int index = 1;
        for (CandidateFile candidate : ranked) {
            builder.append(index++)
                    .append(". ")
                    .append(candidate.relativePath())
                    .append(" (score=")
                    .append(candidate.score())
                    .append(")\n");
        }

        builder.append("\n关键代码片段:\n");
        List<String> snippets = buildSnippets(ranked, keywords);
        if (snippets.isEmpty()) {
            builder.append("（未抽取到关键片段，建议直接读取候选文件）");
        } else {
            for (String snippet : snippets) {
                builder.append(snippet).append('\n');
            }
        }

        return trim(builder.toString().trim());
    }

    private List<CandidateFile> rankCandidateFiles(List<String> keywords) {
        List<CandidateFile> result = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(rootPath, maxDepth)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSearchableFile)
                    .forEach(file -> {
                        CandidateFile candidate = scoreFile(file, keywords);
                        if (candidate != null) {
                            result.add(candidate);
                        }
                    });
        } catch (IOException ex) {
            throw new BusinessException(50045, "工作区任务分析失败: " + ex.getMessage());
        }

        return result.stream()
                .sorted(Comparator.comparingInt(CandidateFile::score).reversed()
                        .thenComparing(CandidateFile::relativePath))
                .limit(maxCandidates)
                .toList();
    }

    private CandidateFile scoreFile(Path file, List<String> keywords) {
        String relative = toRelative(file);
        String lowerRelative = relative.toLowerCase(Locale.ROOT);
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        String content = readSafe(file);
        if (!StringUtils.hasText(content)) {
            return null;
        }

        int score = 0;
        int hitCount = 0;
        String lowerContent = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            String normalized = keyword.toLowerCase(Locale.ROOT);
            if (name.contains(normalized)) {
                score += 6;
                hitCount++;
            }
            if (lowerRelative.contains(normalized)) {
                score += 3;
                hitCount++;
            }
            if (lowerContent.contains(normalized)) {
                score += 2;
                hitCount++;
            }
        }

        if (score == 0) {
            return null;
        }
        return new CandidateFile(file, relative, score, hitCount, content);
    }

    private List<String> buildSnippets(List<CandidateFile> ranked, List<String> keywords) {
        List<String> snippets = new ArrayList<>();
        for (CandidateFile candidate : ranked) {
            if (snippets.size() >= maxSnippets) {
                break;
            }
            String snippet = extractSnippet(candidate, keywords);
            if (StringUtils.hasText(snippet)) {
                snippets.add("- " + candidate.relativePath() + ": " + snippet);
            }
        }
        return snippets;
    }

    private String extractSnippet(CandidateFile candidate, List<String> keywords) {
        String[] lines = candidate.content().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String normalized = lines[i].toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return "line " + (i + 1) + " -> " + compact(joinContext(lines, i));
                }
            }
        }
        return compact(candidate.content());
    }

    private String joinContext(String[] lines, int hitIndex) {
        int start = Math.max(0, hitIndex - 1);
        int end = Math.min(lines.length - 1, hitIndex + 2);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(lines[i].trim());
        }
        return builder.toString();
    }

    private List<String> extractKeywords(String goal) {
        Set<String> keywords = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(goal);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (value.length() >= 2 && value.length() <= 40) {
                keywords.add(value);
            }
        }
        return new ArrayList<>(keywords).subList(0, Math.min(keywords.size(), 5));
    }

    private boolean isSearchableFile(Path file) {
        if (isIgnored(file)) {
            return false;
        }
        try {
            if (Files.size(file) > maxFileSizeBytes) {
                return false;
            }
        } catch (IOException ex) {
            return false;
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return name.equalsIgnoreCase("Dockerfile") || name.equalsIgnoreCase("Jenkinsfile");
        }
        String ext = name.substring(idx + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
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

    private String readSafe(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private String compact(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (value.length() <= 160) {
            return value;
        }
        return value.substring(0, 160) + "...";
    }

    private String trim(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxReadChars) {
            return text;
        }
        return text.substring(0, maxReadChars) + "\n...<TRUNCATED>";
    }

    private String toRelative(Path path) {
        try {
            return rootPath.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
        } catch (Exception ex) {
            return path.toString().replace("\\", "/");
        }
    }

    private record CandidateFile(Path path, String relativePath, int score, int hitCount, String content) {
    }
}
