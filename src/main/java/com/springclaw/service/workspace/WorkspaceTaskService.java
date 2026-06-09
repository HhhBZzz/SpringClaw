package com.springclaw.service.workspace;

import com.springclaw.common.exception.BusinessException;
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

        String normalizedGoal = goal.trim();
        if (looksLikeProjectStructureQuestion(normalizedGoal)) {
            return analyzeProjectStructure(normalizedGoal);
        }

        List<String> keywords = extractKeywords(goal);
        if (keywords.isEmpty()) {
            keywords = List.of(normalizedGoal);
        }

        List<CandidateFile> ranked = rankCandidateFiles(keywords);
        if (ranked.isEmpty()) {
            return "未在工作区内找到和任务相关的文件。task=" + goal.trim();
        }

        StringBuilder builder = new StringBuilder();
        builder.append("WORKSPACE_TASK\n")
                .append("任务: ").append(normalizedGoal).append("\n\n")
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

        return TextUtils.truncate(builder.toString().trim(), maxReadChars);
    }

    private String analyzeProjectStructure(String goal) {
        StringBuilder builder = new StringBuilder();
        builder.append("项目结构概览\n")
                .append("任务: ").append(goal).append("\n\n")
                .append("整体判断:\n");

        List<String> stack = detectProjectStack();
        if (stack.isEmpty()) {
            builder.append("- 这是一个普通代码工作区，暂未识别到 Maven、前端或 skill 包等典型入口。\n");
        } else {
            stack.forEach(item -> builder.append("- ").append(item).append('\n'));
        }

        builder.append("\n核心目录:\n");
        List<String> directories = describeKnownDirectories();
        if (directories.isEmpty()) {
            builder.append("- 当前工作区没有命中预设的核心目录，建议先查看根目录文件。\n");
        } else {
            directories.forEach(item -> builder.append("- ").append(item).append('\n'));
        }

        builder.append("\n关键入口文件:\n");
        List<String> entryFiles = describeEntryFiles();
        if (entryFiles.isEmpty()) {
            builder.append("- 暂未识别到典型入口文件。\n");
        } else {
            entryFiles.forEach(item -> builder.append("- ").append(item).append('\n'));
        }

        builder.append("\n建议阅读顺序:\n")
                .append("1. 先看根目录构建文件和 application.yml，确认技术栈与运行配置。\n")
                .append("2. 再看 controller/service/tool/skill 相关目录，理解请求如何进入业务和工具执行。\n")
                .append("3. 最后看 frontend Vue 工程，确认页面如何调用后端接口。");

        return TextUtils.truncate(builder.toString().trim(), maxReadChars);
    }

    private List<String> detectProjectStack() {
        List<String> stack = new ArrayList<>();
        if (exists("pom.xml") && isDirectory("src/main/java")) {
            stack.add("Spring Boot 后端: Maven 项目，核心代码在 src/main/java。");
        } else if (exists("build.gradle") || exists("build.gradle.kts")) {
            stack.add("Java/Gradle 后端: 使用 Gradle 构建。");
        }
        if (exists("frontend/package.json")) {
            String frontendPackage = readSafe(rootPath.resolve("frontend/package.json"));
            String stackName = frontendPackage.contains("\"vue\"") || frontendPackage.contains("@vitejs/plugin-vue")
                    ? "Vue/Vite 前端"
                    : "Node 前端";
            stack.add(stackName + ": 前端工程在 frontend。");
        }
        if (isDirectory("skills")) {
            stack.add("Skill 体系: skills 目录直接存放可被 Agent 调用的能力包。");
        }
        if (exists("docker-compose.yml")) {
            stack.add("Docker 编排: docker-compose.yml 管理 MySQL、Redis、RabbitMQ 等本地依赖。");
        }
        return stack;
    }

    private List<String> describeKnownDirectories() {
        List<String> directories = new ArrayList<>();
        addDirectoryDescription(directories, "src/main/java", "Java 后端源码，通常包含 controller、service、domain、tool、strategy 等模块。");
        addDirectoryDescription(directories, "src/main/resources", "后端配置、SQL、静态资源目录。");
        addDirectoryDescription(directories, "frontend", "独立前端工程，开发态通常由 Vite 启动。");
        addDirectoryDescription(directories, "skills", "项目内 skill 单源目录，每个子目录通常有 SKILL.md 和执行脚本。");
        addDirectoryDescription(directories, "docs", "项目文档和设计说明。");
        addDirectoryDescription(directories, "http", "接口调试样例。");
        return directories;
    }

    private List<String> describeEntryFiles() {
        List<String> files = new ArrayList<>();
        addFileDescription(files, "pom.xml", "Maven 依赖和构建入口。");
        addFileDescription(files, "src/main/resources/application.yml", "后端核心配置入口。");
        addFileDescription(files, "docker-compose.yml", "本地依赖编排入口。");
        addFileDescription(files, "frontend/package.json", "前端依赖和启动脚本入口。");
        addFileDescription(files, "SOUL.md", "Agent 系统人格/系统提示词来源。");
        findFirstApplicationFile().ifPresent(path ->
                files.add(path + ": Spring Boot 启动类。"));
        return files;
    }

    private java.util.Optional<String> findFirstApplicationFile() {
        Path javaRoot = rootPath.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return java.util.Optional.empty();
        }
        try (Stream<Path> stream = Files.walk(javaRoot, Math.min(maxDepth, 8))) {
            return stream.filter(Files::isRegularFile)
                    .map(this::toRelative)
                    .filter(path -> path.endsWith("Application.java"))
                    .sorted()
                    .findFirst();
        } catch (IOException ex) {
            return java.util.Optional.empty();
        }
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

    private boolean looksLikeProjectStructureQuestion(String goal) {
        String lower = goal.toLowerCase(Locale.ROOT);
        boolean explicitStructure = TextUtils.containsAny(lower, "项目结构", "工程结构", "目录结构", "整体结构", "项目架构", "代码结构");
        boolean structureIntent = TextUtils.containsAny(lower, "结构", "架构", "目录", "模块", "组成", "怎么组织", "怎样", "概览", "梳理");
        boolean projectScope = TextUtils.containsAny(lower, "项目", "工程", "代码库", "仓库", "整体", "当前");
        boolean preciseLocation = TextUtils.containsAny(lower, "在哪个文件", "实现在哪", "源码位置", "代码位置", "哪个类", "哪个方法");
        return explicitStructure || (projectScope && structureIntent && !preciseLocation);
    }

    private void addDirectoryDescription(List<String> target, String relativePath, String description) {
        if (isDirectory(relativePath)) {
            target.add(relativePath + ": " + description);
        }
    }

    private void addFileDescription(List<String> target, String relativePath, String description) {
        if (exists(relativePath)) {
            target.add(relativePath + ": " + description);
        }
    }

    private boolean exists(String relativePath) {
        return Files.exists(rootPath.resolve(relativePath));
    }

    private boolean isDirectory(String relativePath) {
        return Files.isDirectory(rootPath.resolve(relativePath));
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
