package com.springclaw.service.workspace;

import com.springclaw.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地工作区审查服务。
 *
 * 只读扫描受控项目根目录，面向“像 Codex/OpenClaw 一样看本地项目源码并审查”的场景。
 * 敏感文件只报告风险位置，不输出密钥值。
 */
@Service
public class WorkspaceReviewService {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "yml", "yaml", "md", "txt", "json", "sql",
            "properties", "js", "ts", "tsx", "jsx", "html", "css", "sh", "py", "vue"
    );
    private static final List<String> GENERATED_OR_DEPENDENCY_DIRS = List.of(
            ".git", "target", "node_modules", "dist", "build", ".idea", ".vscode", "__pycache__"
    );
    private static final List<String> SECRET_HINTS = List.of(
            "api_key", "apikey", "secret", "password", "token", "access-key", "access_key", "private_key"
    );

    private final Path rootPath;
    private final int maxDepth;
    private final int maxFiles;
    private final int maxFindings;
    private final long maxFileSizeBytes;

    public WorkspaceReviewService(
            @Value("${springclaw.tools.file.root:${user.dir}}") String root,
            @Value("${springclaw.tools.workspace.max-depth:8}") int maxDepth,
            @Value("${springclaw.tools.workspace.review-max-files:3000}") int maxFiles,
            @Value("${springclaw.tools.workspace.review-max-findings:40}") int maxFindings,
            @Value("${springclaw.tools.workspace.max-file-size-kb:512}") int maxFileSizeKb) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxDepth = Math.max(2, maxDepth);
        this.maxFiles = Math.max(100, maxFiles);
        this.maxFindings = Math.max(5, maxFindings);
        this.maxFileSizeBytes = Math.max(64, maxFileSizeKb) * 1024L;
    }

    public String reviewWorkspace(String goal) {
        String normalizedGoal = StringUtils.hasText(goal) ? goal.trim() : "审查当前项目";
        if (!Files.isDirectory(rootPath)) {
            throw new BusinessException(40081, "工作区根目录不存在: " + rootPath);
        }

        ReviewState state = scanWorkspace();
        StringBuilder builder = new StringBuilder();
        builder.append("LOCAL_WORKSPACE_REVIEW\n")
                .append("目标: ").append(normalizedGoal).append('\n')
                .append("根目录: ").append(rootPath).append("\n\n");

        appendOverview(builder, state);
        appendStack(builder, state);
        appendStructure(builder, state);
        appendFindings(builder, state);
        appendReadingOrder(builder, state);

        return builder.toString().trim();
    }

    private ReviewState scanWorkspace() {
        ReviewState state = new ReviewState();
        try {
            Files.walkFileTree(rootPath, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isGeneratedOrDependencyPath(dir)) {
                        state.skippedGenerated = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    state.directories.add(toRelative(dir));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (state.files.size() >= maxFiles) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!Files.isRegularFile(file) || isGeneratedOrDependencyPath(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    state.files.add(toRelative(file));
                    state.extensionCounts.merge(extensionOf(file), 1, Integer::sum);
                    inspectFile(file, state);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new BusinessException(50081, "工作区审查失败: " + ex.getMessage());
        }
        return state;
    }

    private void inspectFile(Path file, ReviewState state) {
        String relative = toRelative(file);
        detectStackFromPath(relative, state);

        if (isSensitiveFileName(relative)) {
            addFinding(state, "敏感配置", relative + ": 文件名看起来包含密钥/环境配置，已识别但不展示具体值。");
        }
        if (!isLikelyTextFile(file) || fileTooLarge(file)) {
            return;
        }

        String ext = extensionOf(file);
        long totalLines = 0;
        long blankLines = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                totalLines++;
                if (line.trim().isEmpty()) {
                    blankLines++;
                }
                inspectLine(relative, lineNo, line, state);
                if (state.findings.size() >= maxFindings) {
                    // Still count remaining lines for statistics
                    while (reader.readLine() != null) {
                        lineNo++;
                        totalLines++;
                    }
                    break;
                }
            }
        } catch (Exception ignore) {
            addFinding(state, "不可读文件", relative + ": 文件读取失败，已跳过。");
        }

        // Accumulate line statistics by extension
        long codeLines = totalLines - blankLines;
        long[] counts = state.linesByExtension.computeIfAbsent(ext, k -> new long[3]);
        counts[0] += totalLines;
        counts[1] += codeLines;
        counts[2] += blankLines;
    }

    private void detectStackFromPath(String relative, ReviewState state) {
        if ("pom.xml".equals(relative) || relative.startsWith("src/main/java/")) {
            state.stack.add("Spring Boot / Maven 后端");
        }
        if ("build.gradle".equals(relative) || "build.gradle.kts".equals(relative)) {
            state.stack.add("Gradle 后端");
        }
        if ("frontend/package.json".equals(relative)) {
            state.stack.add("Vue/Vite 前端");
        }
        if (relative.startsWith("skills/")) {
            state.stack.add("Skill 体系");
        }
        if ("docker-compose.yml".equals(relative)) {
            state.stack.add("Docker 本地依赖编排");
        }
        if (relative.startsWith("src/test/")) {
            state.hasTests = true;
        }
    }

    private void inspectLine(String relative, int lineNo, String line, ReviewState state) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (containsSecretHint(lower)) {
            addFinding(state, "敏感配置", relative + ":" + lineNo + ": 疑似敏感配置，值已隐藏。");
            return;
        }
        if (lower.contains("todo") || lower.contains("fixme")) {
            addFinding(state, "待办代码", relative + ":" + lineNo + ": " + compact(line));
        }
        if (lower.contains("system.exit(")) {
            addFinding(state, "运行风险", relative + ":" + lineNo + ": 出现 System.exit，服务端代码需确认不会中断进程。");
        }
        if (lower.contains("printstacktrace(")) {
            addFinding(state, "异常处理", relative + ":" + lineNo + ": 出现 printStackTrace，建议接入日志与错误码。");
        }
    }

    private void appendOverview(StringBuilder builder, ReviewState state) {
        builder.append("项目概览:\n")
                .append("- 文件数: ").append(state.files.size()).append('\n')
                .append("- 目录数: ").append(state.directories.size()).append('\n')
                .append("- 测试目录: ").append(state.hasTests ? "已发现" : "未发现明显 src/test 覆盖").append('\n');
        if (state.skippedGenerated) {
            builder.append("- 已跳过生成/依赖目录: ").append(String.join(", ", GENERATED_OR_DEPENDENCY_DIRS)).append('\n');
        }
        if (!state.extensionCounts.isEmpty()) {
            builder.append("- 主要文件类型: ");
            builder.append(state.extensionCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(6)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("-"));
            builder.append('\n');
        }
        // Code line statistics
        if (!state.linesByExtension.isEmpty()) {
            long totalAll = 0, codeAll = 0, blankAll = 0;
            for (long[] counts : state.linesByExtension.values()) {
                totalAll += counts[0];
                codeAll += counts[1];
                blankAll += counts[2];
            }
            builder.append("- 代码总行数: ").append(totalAll)
                    .append("（代码行 ").append(codeAll).append("，空行 ").append(blankAll).append("）\n");
            // Top languages by code lines
            builder.append("- 按语言分布: ");
            builder.append(state.linesByExtension.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                    .limit(5)
                    .map(entry -> entry.getKey() + " " + entry.getValue()[1] + "行")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("-"));
            builder.append('\n');
        }
        builder.append('\n');
    }

    private void appendStack(StringBuilder builder, ReviewState state) {
        builder.append("识别到的技术栈:\n");
        if (state.stack.isEmpty()) {
            builder.append("- 暂未识别到明确技术栈，请先查看构建文件。\n\n");
            return;
        }
        state.stack.forEach(item -> builder.append("- ").append(item).append('\n'));
        builder.append('\n');
    }

    private void appendStructure(StringBuilder builder, ReviewState state) {
        builder.append("建议优先审查的入口:\n");
        appendIfExists(builder, state, "pom.xml", "Maven 依赖与构建入口");
        appendIfExists(builder, state, "src/main/resources/application.yml", "后端核心配置");
        appendIfPrefixExists(builder, state, "src/main/java", "Java 后端源码");
        appendIfPrefixExists(builder, state, "frontend", "前端工程");
        appendIfPrefixExists(builder, state, "skills", "Agent skill 单源目录");
        appendIfExists(builder, state, "docker-compose.yml", "本地依赖编排");
        builder.append('\n');
    }

    private void appendFindings(StringBuilder builder, ReviewState state) {
        builder.append("审查发现:\n");
        if (state.findings.isEmpty()) {
            builder.append("- 暂未发现明显风险；建议继续按具体模块做深度代码 review。\n\n");
            return;
        }
        state.findings.stream()
                .limit(maxFindings)
                .forEach(finding -> builder.append("- [").append(finding.type()).append("] ")
                        .append(finding.message()).append('\n'));
        builder.append('\n');
    }

    private void appendReadingOrder(StringBuilder builder, ReviewState state) {
        builder.append("推荐下一步:\n")
                .append("1. 先看构建与配置文件，确认依赖、端口、模型和数据库连接。\n")
                .append("2. 再看 controller/service/tool/skill 的调用链，确认 Agent 如何把用户问题变成工具执行。\n")
                .append("3. 最后针对审查发现逐个读文件，避免一次把所有源码塞给模型造成上下文污染。");
    }

    private void appendIfExists(StringBuilder builder, ReviewState state, String path, String description) {
        if (state.files.contains(path)) {
            builder.append("- ").append(path).append(": ").append(description).append('\n');
        }
    }

    private void appendIfPrefixExists(StringBuilder builder, ReviewState state, String prefix, String description) {
        boolean exists = state.files.stream().anyMatch(path -> path.startsWith(prefix + "/"))
                || state.directories.stream().anyMatch(path -> path.equals(prefix) || path.startsWith(prefix + "/"));
        if (exists) {
            builder.append("- ").append(prefix).append(": ").append(description).append('\n');
        }
    }

    private void addFinding(ReviewState state, String type, String message) {
        if (state.findings.size() >= maxFindings) {
            return;
        }
        state.findings.add(new ReviewFinding(type, message));
    }

    private boolean isGeneratedOrDependencyPath(Path path) {
        String relative = toRelative(path).toLowerCase(Locale.ROOT);
        for (String dir : GENERATED_OR_DEPENDENCY_DIRS) {
            String normalized = dir.toLowerCase(Locale.ROOT);
            if (relative.equals(normalized)
                    || relative.startsWith(normalized + "/")
                    || relative.contains("/" + normalized + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitiveFileName(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.endsWith(".env")
                || lower.contains(".env.")
                || lower.contains("secret")
                || lower.contains("credential")
                || lower.contains("private-key");
    }

    private boolean containsSecretHint(String lowerLine) {
        for (String hint : SECRET_HINTS) {
            if (lowerLine.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyTextFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (name.equalsIgnoreCase("Dockerfile") || name.equalsIgnoreCase("Jenkinsfile")) {
            return true;
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(name.substring(idx + 1).toLowerCase(Locale.ROOT));
    }

    private boolean fileTooLarge(Path file) {
        try {
            return Files.size(file) > maxFileSizeBytes;
        } catch (IOException ex) {
            return true;
        }
    }

    private String extensionOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "no-ext";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String compact(String line) {
        String compact = line == null ? "" : line.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 160) {
            return compact;
        }
        return compact.substring(0, 160) + "...";
    }

    private String toRelative(Path path) {
        try {
            return rootPath.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
        } catch (Exception ex) {
            return path.toString().replace("\\", "/");
        }
    }

    private static final class ReviewState {
        private final Set<String> files = new LinkedHashSet<>();
        private final Set<String> directories = new LinkedHashSet<>();
        private final Set<String> stack = new LinkedHashSet<>();
        private final Map<String, Integer> extensionCounts = new LinkedHashMap<>();
        private final Map<String, long[]> linesByExtension = new LinkedHashMap<>(); // ext -> [total, code, blank]
        private final List<ReviewFinding> findings = new ArrayList<>();
        private boolean skippedGenerated;
        private boolean hasTests;
    }

    private record ReviewFinding(String type, String message) {
    }
}
