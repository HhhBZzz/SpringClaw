package com.springclaw.service.files;

import com.springclaw.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 本地授权文件系统服务。
 *
 * 只读访问显式授权根目录。项目内 FileToolPack 也复用这里的路径边界，
 * 避免“项目文件”和“电脑授权文件”出现两套安全实现。
 */
@Service
public class LocalFilesystemService {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "yml", "yaml", "md", "txt", "json", "sql",
            "properties", "js", "ts", "tsx", "jsx", "html", "css", "sh", "py", "vue",
            "csv", "log"
    );

    private final List<AuthorizedRoot> roots;
    private final List<String> deniedPathTokens;
    private final int maxReadChars;
    private final int maxDepth;
    private final int maxFileResults;
    private final int maxTextHits;
    private final long maxFileSizeBytes;

    public LocalFilesystemService(
            @Value("${springclaw.local-files.roots:${springclaw.tools.file.root:${user.dir}}}") String roots,
            @Value("${springclaw.local-files.deny-paths:.git,node_modules,target,dist,build,.ssh,.gnupg,.aws,.kube,Library/Keychains,Library/Application Support/Google/Chrome,Library/Application Support/Firefox,.env}") String deniedPaths,
            @Value("${springclaw.local-files.max-read-chars:12000}") int maxReadChars,
            @Value("${springclaw.local-files.max-depth:8}") int maxDepth,
            @Value("${springclaw.local-files.max-file-results:100}") int maxFileResults,
            @Value("${springclaw.local-files.max-text-hits:50}") int maxTextHits,
            @Value("${springclaw.local-files.max-file-size-kb:512}") int maxFileSizeKb) {
        this.roots = parseRoots(roots);
        this.deniedPathTokens = parseDeniedPaths(deniedPaths);
        this.maxReadChars = Math.max(2048, maxReadChars);
        this.maxDepth = Math.max(2, maxDepth);
        this.maxFileResults = Math.max(10, maxFileResults);
        this.maxTextHits = Math.max(5, maxTextHits);
        this.maxFileSizeBytes = Math.max(64, maxFileSizeKb) * 1024L;
    }

    public String listAuthorizedRoots() {
        if (roots.isEmpty()) {
            return "未配置本地授权目录。";
        }
        List<String> lines = new ArrayList<>();
        for (AuthorizedRoot root : roots) {
            lines.add(root.alias() + ": " + root.path());
        }
        return String.join("\n", lines);
    }

    public String listFiles(String rootRef, String relativeDir) {
        Path dir = resolveSafePath(rootRef, relativeDir, true);
        if (!Files.isDirectory(dir)) {
            return "目录不存在: " + printablePath(rootRef, relativeDir);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> lines = stream
                    .filter(path -> !isDenied(path))
                    .limit(200)
                    .map(path -> {
                        AuthorizedRoot root = findContainingRoot(path);
                        return (Files.isDirectory(path) ? "[D] " : "[F] ") + root.alias() + ":" + toRelative(root, path);
                    })
                    .toList();
            return lines.isEmpty() ? "目录为空或只有受保护文件: " + printablePath(rootRef, relativeDir) : String.join("\n", lines);
        } catch (IOException ex) {
            throw new BusinessException(50091, "本地授权目录列举失败: " + ex.getMessage());
        }
    }

    public String readTextFile(String rootRef, String relativeFilePath) {
        Path file = resolveSafePath(rootRef, relativeFilePath, false);
        if (!Files.isRegularFile(file)) {
            return "文件不存在: " + printablePath(rootRef, relativeFilePath);
        }
        if (!isLikelyTextFile(file)) {
            throw new BusinessException(40093, "仅允许读取文本文件: " + displayPath(file));
        }
        if (fileTooLarge(file)) {
            throw new BusinessException(40094, "文件过大，禁止直接读取: " + displayPath(file));
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > maxReadChars) {
                return content.substring(0, maxReadChars) + "\n...<TRUNCATED>";
            }
            return content;
        } catch (IOException ex) {
            throw new BusinessException(50092, "本地授权文件读取失败: " + ex.getMessage());
        }
    }

    public String searchFiles(String keyword) {
        String key = normalizeKeyword(keyword);
        List<String> results = new ArrayList<>();
        walkAuthorizedFiles(file -> {
            if (results.size() >= maxFileResults) {
                return false;
            }
            String fileName = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.contains(key.toLowerCase(Locale.ROOT))) {
                results.add(displayPath(file));
            }
            return true;
        });
        return results.isEmpty() ? "未找到匹配文件: " + key : String.join("\n", results);
    }

    public String searchFilesByGlob(String rootRef, String pattern) {
        String glob = StringUtils.hasText(pattern) ? pattern.trim() : "*";
        AuthorizedRoot root = resolveRoot(rootRef);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<String> results = new ArrayList<>();
        walkFiles(root, file -> {
            if (results.size() >= maxFileResults) {
                return false;
            }
            Path relative = root.path().relativize(file);
            if (matcher.matches(relative) || matcher.matches(relative.getFileName())) {
                results.add(displayPath(file));
            }
            return true;
        });
        return results.isEmpty() ? "未找到匹配文件: " + glob : String.join("\n", results);
    }

    public String grepText(String keyword) {
        return grepText(keyword, "*");
    }

    public String grepText(String keyword, String filePattern) {
        String key = normalizeKeyword(keyword);
        String lowerKey = key.toLowerCase(Locale.ROOT);
        String glob = StringUtils.hasText(filePattern) ? filePattern.trim() : "*";
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<String> hits = new ArrayList<>();
        walkAuthorizedFiles(file -> {
            if (hits.size() >= maxTextHits) {
                return false;
            }
            AuthorizedRoot root = findContainingRoot(file);
            Path relative = root.path().relativize(file);
            if (!isLikelyTextFile(file) || fileTooLarge(file)
                    || (!matcher.matches(relative) && !matcher.matches(relative.getFileName()))) {
                return true;
            }
            grepFile(root, file, lowerKey, hits);
            return hits.size() < maxTextHits;
        });
        return hits.isEmpty() ? "未找到关键词命中: " + key : String.join("\n", hits);
    }

    private void grepFile(AuthorizedRoot root, Path file, String lowerKey, List<String> hits) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null && hits.size() < maxTextHits) {
                lineNo++;
                if (line.toLowerCase(Locale.ROOT).contains(lowerKey)) {
                    hits.add(root.alias() + ":" + toRelative(root, file) + ":" + lineNo + ": " + compactLine(line));
                }
            }
        } catch (Exception ignore) {
            // 跳过单个不可读文件，不影响整体搜索。
        }
    }

    private void walkAuthorizedFiles(FileVisitor visitor) {
        for (AuthorizedRoot root : roots) {
            boolean keepGoing = walkFiles(root, visitor);
            if (!keepGoing) {
                return;
            }
        }
    }

    private boolean walkFiles(AuthorizedRoot root, FileVisitor visitor) {
        if (!Files.isDirectory(root.path())) {
            return true;
        }
        WalkState state = new WalkState();
        try {
            Files.walkFileTree(root.path(), Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root.path()) && isDenied(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (state.scannedFiles >= maxFileResults * 20L) {
                        state.keepGoing = false;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!Files.isRegularFile(file) || isDenied(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    state.scannedFiles++;
                    if (!visitor.visit(file)) {
                        state.keepGoing = false;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return state.keepGoing;
        } catch (IOException ex) {
            throw new BusinessException(50093, "本地授权文件搜索失败: " + ex.getMessage());
        }
    }

    private Path resolveSafePath(String rootRef, String relativePath, boolean allowBlankAsRoot) {
        AuthorizedRoot root = resolveRoot(rootRef);
        String value = StringUtils.hasText(relativePath) ? relativePath.trim() : "";
        if (!StringUtils.hasText(value) && allowBlankAsRoot) {
            return root.path();
        }
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(40091, "本地文件路径不能为空");
        }

        Path target = root.path().resolve(value).normalize().toAbsolutePath();
        if (!target.startsWith(root.path())) {
            throw new BusinessException(40092, "文件路径越界，禁止访问授权目录外路径");
        }
        if (isDenied(target)) {
            throw new BusinessException(40095, "该路径受保护，禁止读取: " + displayPath(target));
        }
        return target;
    }

    private AuthorizedRoot resolveRoot(String rootRef) {
        if (roots.isEmpty()) {
            throw new BusinessException(40090, "未配置本地授权目录");
        }
        if (!StringUtils.hasText(rootRef)) {
            return roots.get(0);
        }
        String value = rootRef.trim();
        for (AuthorizedRoot root : roots) {
            if (root.alias().equalsIgnoreCase(value)
                    || root.path().toString().equals(value)
                    || root.path().getFileName() != null && root.path().getFileName().toString().equalsIgnoreCase(value)) {
                return root;
            }
        }
        throw new BusinessException(40096, "未找到授权根目录: " + value);
    }

    private AuthorizedRoot findContainingRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (AuthorizedRoot root : roots) {
            if (normalized.startsWith(root.path())) {
                return root;
            }
        }
        throw new BusinessException(40097, "文件不属于任何授权根目录: " + path);
    }

    private boolean isDenied(Path path) {
        AuthorizedRoot root;
        try {
            root = findContainingRoot(path);
        } catch (BusinessException ex) {
            return true;
        }
        String relative = toRelative(root, path).toLowerCase(Locale.ROOT);
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            return true;
        }
        for (String denied : deniedPathTokens) {
            if (!StringUtils.hasText(denied)) {
                continue;
            }
            String token = denied.toLowerCase(Locale.ROOT).replace("\\", "/");
            if (relative.equals(token)
                    || relative.startsWith(token + "/")
                    || relative.contains("/" + token + "/")
                    || relative.endsWith("/" + token)
                    || fileName.equals(token)) {
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

    private String displayPath(Path path) {
        AuthorizedRoot root = findContainingRoot(path);
        return root.alias() + ":" + toRelative(root, path);
    }

    private String toRelative(AuthorizedRoot root, Path path) {
        String relative = root.path().relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
        return relative.isBlank() ? "." : relative;
    }

    private String printablePath(String rootRef, String relative) {
        String root = StringUtils.hasText(rootRef) ? rootRef.trim() : "root1";
        String path = StringUtils.hasText(relative) ? relative.trim() : ".";
        return root + ":" + path;
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(40098, "搜索关键词不能为空");
        }
        String value = keyword.trim();
        if (value.length() < 2) {
            throw new BusinessException(40099, "搜索关键词至少 2 个字符");
        }
        return value;
    }

    private String compactLine(String line) {
        String compact = line == null ? "" : line.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private List<AuthorizedRoot> parseRoots(String rawRoots) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        if (StringUtils.hasText(rawRoots)) {
            for (String token : rawRoots.split(",")) {
                if (StringUtils.hasText(token)) {
                    paths.add(Path.of(token.trim()).toAbsolutePath().normalize());
                }
            }
        }
        if (paths.isEmpty()) {
            paths.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
        }
        List<AuthorizedRoot> result = new ArrayList<>();
        int index = 1;
        for (Path path : paths) {
            result.add(new AuthorizedRoot("root" + index++, path));
        }
        return List.copyOf(result);
    }

    private List<String> parseDeniedPaths(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            if (StringUtils.hasText(token)) {
                values.add(token.trim().replace("\\", "/"));
            }
        }
        return List.copyOf(values);
    }

    private record AuthorizedRoot(String alias, Path path) {
    }

    @FunctionalInterface
    private interface FileVisitor {
        boolean visit(Path file);
    }

    private static final class WalkState {
        private long scannedFiles;
        private boolean keepGoing = true;
    }
}
