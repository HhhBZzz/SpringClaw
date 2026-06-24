package com.springclaw.service.memory;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3A1 Task 8：把评审过的 Markdown 项目记忆文件映射为带类型的
 * {@link ProjectMemoryItem}。
 *
 * <p>文件名 → {@link ProjectMemoryItem.SourceType} 映射：
 * <pre>
 *   project-brief.md          → PROJECT_BRIEF
 *   current-state.md          → CURRENT_STATE
 *   architecture-decisions.md → ARCHITECTURE_DECISION
 *   agent-learnings.md        → APPROVED_LEARNING（含 approved/active 条目时）
 *   progress.md               → PROGRESS
 *   user-preferences.md       → USER_PREFERENCE
 *   其他 *.md                 → OTHER_REVIEWED_PROJECT_MEMORY
 * </pre>
 *
 * <p>每个 item 带 sourcePath、type、完整 content（不施加全局字符截断）、SHA-256 contentHash、
 * review status、文件修改时间。非 .md 文件不读。agent-learnings.md 的 review status
 * 取文件内最高级状态（approved > active > candidate > rejected）。
 */
@Component
public class MarkdownProjectMemorySource implements ProjectMemorySource {

    private static final Logger log = LoggerFactory.getLogger(MarkdownProjectMemorySource.class);
    private static final Pattern LEARNING_STATUS =
            Pattern.compile("(?im)^-\\s*status:\\s*(\\S+)\\s*$");

    private static final Map<String, ProjectMemoryItem.SourceType> FILE_TYPE_MAP = new HashMap<>();

    static {
        FILE_TYPE_MAP.put("project-brief.md", ProjectMemoryItem.SourceType.PROJECT_BRIEF);
        FILE_TYPE_MAP.put("current-state.md", ProjectMemoryItem.SourceType.CURRENT_STATE);
        FILE_TYPE_MAP.put("architecture-decisions.md", ProjectMemoryItem.SourceType.ARCHITECTURE_DECISION);
        FILE_TYPE_MAP.put("agent-learnings.md", ProjectMemoryItem.SourceType.APPROVED_LEARNING);
        FILE_TYPE_MAP.put("progress.md", ProjectMemoryItem.SourceType.PROGRESS);
        FILE_TYPE_MAP.put("user-preferences.md", ProjectMemoryItem.SourceType.USER_PREFERENCE);
    }

    private static final List<String> ORDERED_FILES = List.of(
            "project-brief.md",
            "current-state.md",
            "architecture-decisions.md",
            "agent-learnings.md",
            "progress.md",
            "user-preferences.md"
    );

    private final Path rootPath;

    @org.springframework.beans.factory.annotation.Autowired
    public MarkdownProjectMemorySource(
            @Value("${springclaw.memory.bank-root:${user.dir}/docs/memory-bank}") String root) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
    }

    /** 测试构造器：直接指定根目录。 */
    public MarkdownProjectMemorySource(Path rootPath) {
        this.rootPath = rootPath.toAbsolutePath().normalize();
    }

    @Override
    public List<ProjectMemoryItem> read(MemoryScope scope) {
        if (!Files.isDirectory(rootPath)) {
            return List.of();
        }
        List<ProjectMemoryItem> items = new ArrayList<>();
        try (var stream = Files.list(rootPath)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparingInt(this::fileOrder)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                ProjectMemoryItem item = readItem(file);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (IOException ex) {
            log.warn("读取项目记忆目录失败，root={}, reason={}", rootPath, ex.getMessage());
            return List.of();
        }
        return List.copyOf(items);
    }

    private ProjectMemoryItem readItem(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                return null;
            }
            String name = file.getFileName().toString();
            ProjectMemoryItem.SourceType type = FILE_TYPE_MAP.getOrDefault(
                    name, ProjectMemoryItem.SourceType.OTHER_REVIEWED_PROJECT_MEMORY);
            return new ProjectMemoryItem(
                    name,
                    type,
                    content,
                    sha256(content),
                    reviewStatusOf(type, content),
                    updatedAt(file)
            );
        } catch (IOException ex) {
            log.warn("读取项目记忆文件失败，file={}, reason={}", file, ex.getMessage());
            return null;
        }
    }

    private static ProjectMemoryItem.ReviewStatus reviewStatusOf(
            ProjectMemoryItem.SourceType type,
            String content
    ) {
        if (type != ProjectMemoryItem.SourceType.APPROVED_LEARNING) {
            return ProjectMemoryItem.ReviewStatus.APPROVED;
        }
        // agent-learnings.md：取文件内出现的最高级状态。
        Matcher matcher = LEARNING_STATUS.matcher(content);
        boolean hasApproved = false;
        boolean hasActive = false;
        boolean hasCandidate = false;
        boolean hasRejected = false;
        while (matcher.find()) {
            String status = matcher.group(1).toLowerCase(Locale.ROOT);
            switch (status) {
                case "approved" -> hasApproved = true;
                case "active" -> hasActive = true;
                case "candidate" -> hasCandidate = true;
                case "rejected" -> hasRejected = true;
                default -> { }
            }
        }
        if (hasApproved) {
            return ProjectMemoryItem.ReviewStatus.APPROVED;
        }
        if (hasActive) {
            return ProjectMemoryItem.ReviewStatus.ACTIVE;
        }
        if (hasRejected) {
            return ProjectMemoryItem.ReviewStatus.REJECTED;
        }
        if (hasCandidate) {
            return ProjectMemoryItem.ReviewStatus.CANDIDATE;
        }
        // 无显式 status（旧格式）视为 active。
        return ProjectMemoryItem.ReviewStatus.ACTIVE;
    }

    private static Instant updatedAt(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return time.toInstant();
        } catch (IOException ex) {
            return Instant.now();
        }
    }

    private int fileOrder(Path path) {
        String name = path.getFileName().toString();
        int index = ORDERED_FILES.indexOf(name);
        return index < 0 ? ORDERED_FILES.size() : index;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
