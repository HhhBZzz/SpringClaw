package com.springclaw.tool.pack;

import com.springclaw.service.skill.bundle.SkillBundleDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.bundle.SkillUsageRecord;
import com.springclaw.service.skill.bundle.SkillUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Hermes 风格 skill 库工具。
 *
 * 核心约束：先列摘要，再按需打开单个 skill 或附件文件，避免一次性把所有 skill 塞进 prompt。
 */
@Component
public class SkillLibraryToolPack {

    private static final int MAX_SKILL_MARKDOWN_CHARS = 8000;
    private static final int MAX_SUPPORTING_FILE_CHARS = 8000;
    private static final int MAX_SUPPORTING_FILES = 80;
    private static final List<String> ALLOWED_SUPPORT_DIRS = List.of("references", "templates", "scripts", "assets");

    private final boolean enabled;
    private final SkillCatalogService skillCatalogService;
    private final SkillUsageService skillUsageService;

    @Autowired
    public SkillLibraryToolPack(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                                SkillCatalogService skillCatalogService,
                                SkillUsageService skillUsageService) {
        this.enabled = enabled;
        this.skillCatalogService = skillCatalogService;
        this.skillUsageService = skillUsageService;
    }

    public SkillLibraryToolPack(boolean enabled,
                                SkillCatalogService skillCatalogService) {
        this(enabled,
                skillCatalogService,
                new SkillUsageService(enabled, skillCatalogService, new ObjectMapper()));
    }

    @Tool(name = "skills_list", description = "Hermes 风格：列出已安装 skill 的最小摘要，不加载完整 SKILL.md")
    public String skillsList() {
        if (!enabled) {
            return "skills 已关闭（springclaw.skills.enabled=false）";
        }
        List<SkillBundleDefinition> bundles = skillCatalogService.listBundles();
        if (bundles.isEmpty()) {
            return "暂无已安装 skill";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skills_list:");
        for (SkillBundleDefinition bundle : bundles) {
            lines.add("- name=" + bundle.skillId()
                    + ", displayName=" + bundle.name()
                    + ", type=" + bundle.executorType()
                    + ", category=" + bundle.category()
                    + ", description=" + bundle.description());
        }
        lines.add("");
        lines.add("需要查看完整说明时，调用 skill_view(name)。需要查看附件时，调用 skill_view_file(name, filePath)。");
        return String.join("\n", lines);
    }

    @Tool(name = "skill_view", description = "Hermes 风格：查看单个 skill 的完整 SKILL.md 和支持文件列表")
    public String skillView(String name) {
        if (!enabled) {
            return "skills 已关闭（springclaw.skills.enabled=false）";
        }
        Optional<SkillBundleDefinition> optional = findBundle(name);
        if (optional.isEmpty()) {
            return "skill 不存在或未安装: " + safe(name);
        }
        SkillBundleDefinition bundle = optional.get();
        skillUsageService.recordView(bundle.skillId());
        List<String> lines = new ArrayList<>();
        lines.add("skill_view:");
        lines.add("name=" + bundle.skillId());
        lines.add("displayName=" + bundle.name());
        lines.add("type=" + bundle.executorType());
        lines.add("category=" + bundle.category());
        lines.add("entrypoint=" + relative(bundle.bundlePath(), bundle.entrypointPath()));
        lines.add("");
        lines.add("SKILL.md:");
        lines.add(readText(bundle.skillPath(), MAX_SKILL_MARKDOWN_CHARS));
        List<String> supportingFiles = listSupportingFiles(bundle.bundlePath());
        if (!supportingFiles.isEmpty()) {
            lines.add("");
            lines.add("supporting_files:");
            supportingFiles.forEach(path -> lines.add("- " + path));
            lines.add("");
            lines.add("如需读取某个支持文件，调用 skill_view_file(name, filePath)。");
        }
        return String.join("\n", lines);
    }

    @Tool(name = "skill_view_file", description = "Hermes 风格：读取某个 skill 下 references/templates/scripts/assets 的指定文件")
    public String skillViewFile(String name, String filePath) {
        if (!enabled) {
            return "skills 已关闭（springclaw.skills.enabled=false）";
        }
        Optional<SkillBundleDefinition> optional = findBundle(name);
        if (optional.isEmpty()) {
            return "skill 不存在或未安装: " + safe(name);
        }
        SkillBundleDefinition bundle = optional.get();
        String normalizedPath = normalizeRelativePath(filePath);
        if (!StringUtils.hasText(normalizedPath) || !isAllowedSupportingPath(normalizedPath)) {
            return "不允许读取该 skill 文件: " + safe(filePath);
        }
        Path target = bundle.bundlePath().resolve(normalizedPath).toAbsolutePath().normalize();
        if (!target.startsWith(bundle.bundlePath()) || !Files.isRegularFile(target)) {
            return "skill 文件不存在: " + normalizedPath;
        }
        skillUsageService.recordView(bundle.skillId());
        return "skill_view_file:\nname=" + bundle.skillId()
                + "\nfile=" + normalizedPath
                + "\n\n" + readText(target, MAX_SUPPORTING_FILE_CHARS);
    }

    @Tool(name = "skills_status", description = "Hermes 风格：查看 skill 使用次数、查看次数和最近活动，用于后续 curator 收口")
    public String skillsStatus() {
        if (!enabled) {
            return "skills 已关闭（springclaw.skills.enabled=false）";
        }
        List<SkillUsageRecord> records = skillUsageService.listUsage(skillCatalogService.listBundles());
        if (records.isEmpty()) {
            return "暂无 skill 使用记录";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skills_status:");
        for (SkillUsageRecord record : records) {
            lines.add("- name=" + record.skillId()
                    + ", useCount=" + record.useCount()
                    + ", viewCount=" + record.viewCount()
                    + ", patchCount=" + record.patchCount()
                    + ", state=" + record.state()
                    + ", pinned=" + record.pinned()
                    + ", lastUsedAt=" + blank(record.lastUsedAt())
                    + ", lastViewedAt=" + blank(record.lastViewedAt()));
        }
        return String.join("\n", lines);
    }

    private Optional<SkillBundleDefinition> findBundle(String name) {
        String normalized = normalize(name);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        return skillCatalogService.listBundles().stream()
                .filter(bundle -> normalize(bundle.skillId()).equals(normalized)
                        || normalize(bundle.slug()).equals(normalized)
                        || normalize(bundle.name()).equals(normalized))
                .findFirst();
    }

    private List<String> listSupportingFiles(Path skillRoot) {
        List<String> files = new ArrayList<>();
        for (String dirName : ALLOWED_SUPPORT_DIRS) {
            Path dir = skillRoot.resolve(dirName).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir) || !dir.startsWith(skillRoot)) {
                continue;
            }
            try (var stream = Files.walk(dir, 4)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> relative(skillRoot, path)))
                        .limit(MAX_SUPPORTING_FILES)
                        .map(path -> relative(skillRoot, path))
                        .forEach(files::add);
            } catch (IOException ignored) {
                // 支持文件只是辅助信息，单个目录读取失败不影响 skill 主体加载。
            }
        }
        return files.stream().distinct().limit(MAX_SUPPORTING_FILES).toList();
    }

    private boolean isAllowedSupportingPath(String path) {
        return ALLOWED_SUPPORT_DIRS.stream().anyMatch(dir -> path.equals(dir) || path.startsWith(dir + "/"));
    }

    private String normalizeRelativePath(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return "";
        }
        String value = filePath.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.contains("..") || value.startsWith("/") || value.contains("\0")) {
            return "";
        }
        return value;
    }

    private String readText(Path file, int maxChars) {
        if (file == null || !Files.isRegularFile(file)) {
            return "(未找到)";
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() <= maxChars) {
                return text.trim();
            }
            return text.substring(0, maxChars).trim() + "\n...<TRUNCATED>";
        } catch (IOException ex) {
            return "(读取失败: " + ex.getMessage() + ")";
        }
    }

    private String relative(Path root, Path path) {
        if (root == null || path == null) {
            return "";
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return normalizedPath.toString();
        }
        return normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blank(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
