package com.springclaw.service.skill.bundle;

import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * package skill 的 SKILL.md 解析与写回工具。
 * 支持扁平 frontmatter（所有字段在顶层），不再使用嵌套 metadata 路径。
 */
public final class SkillBundleSupport {

    public static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_BOUNDARY = Pattern.compile("(?m)^---\\s*$");
    private static final int DEFAULT_SCRIPT_PRIORITY = 100;
    private static final int DEFAULT_MARKDOWN_PRIORITY = 60;

    private SkillBundleSupport() {
    }

    /**
     * 解析一个 skill bundle 目录，从 SKILL.md 的扁平 frontmatter 读取元数据。
     */
    public static Optional<SkillBundleDefinition> parseBundle(Path bundlePath) {
        Path normalizedBundlePath = bundlePath.toAbsolutePath().normalize();
        Path skillPath = resolveSkillPath(normalizedBundlePath);
        if (skillPath == null || !Files.isRegularFile(skillPath)) {
            return Optional.empty();
        }
        try {
            String markdown = Files.readString(skillPath, StandardCharsets.UTF_8);
            ParsedSkillMarkdown parsed = parseMarkdown(markdown);
            Map<String, Object> fm = parsed.frontmatter();

            String slug = normalizedBundlePath.getFileName() == null
                    ? "skill" : normalizedBundlePath.getFileName().toString();
            // skillId 优先取 frontmatter 中显式指定的 skillId，否则取 displayName，最后回退到目录名
            String skillId = firstNonBlank(getString(fm, "skillId"), slug);
            String name = firstNonBlank(getString(fm, "displayName"), getString(fm, "name"), skillId);
            String description = firstNonBlank(getString(fm, "description"), name + " skill");

            String executorType = normalizeExecutorType(firstNonBlank(
                    getString(fm, "type"),
                    guessExecutorTypeFromEntrypoint(getString(fm, "entrypoint")),
                    "prompt"
            ));

            String entrypoint = firstNonBlank(getString(fm, "entrypoint"), null);
            Path entrypointPath = null;
            if (StringUtils.hasText(entrypoint)) {
                entrypointPath = normalizedBundlePath.resolve(entrypoint).normalize();
                if (!entrypointPath.startsWith(normalizedBundlePath) || !Files.isRegularFile(entrypointPath)) {
                    return Optional.empty();
                }
            }
            if (requiresEntrypoint(executorType) && entrypointPath == null) {
                return Optional.empty();
            }

            String preferredMode = normalizeMode(firstNonBlank(
                    getString(fm, "preferredMode"), "simplified"));
            String contextPolicy = firstNonBlank(
                    getString(fm, "contextPolicy"), "session-only");
            boolean agentVisible = getBoolean(fm, true, "agentVisible");
            boolean enabled = getBoolean(fm, true, "enabled");
            int priority = getInteger(fm,
                    isPromptExecutor(executorType) ? DEFAULT_MARKDOWN_PRIORITY : DEFAULT_SCRIPT_PRIORITY,
                    "priority");
            String category = firstNonBlank(getString(fm, "category"), "general");
            String tier = firstNonBlank(getString(fm, "tier"), "utility");
            String inputHint = firstNonBlank(
                    getString(fm, "inputHint"),
                    isPromptExecutor(executorType) ? "根据 skill 说明执行对应任务。" : "传入自然语言 goal 执行脚本技能。"
            );

            List<String> triggerKeywords = toStringList(fm.get("triggerKeywords"));
            if (triggerKeywords.isEmpty()) {
                triggerKeywords = deriveDefaultKeywords(skillId, name);
            }
            List<String> triggerExamples = toStringList(fm.get("triggerExamples"));
            List<String> toolPacks = normalizeToolPacks(toStringList(fm.get("toolPacks")));

            String sourceType = switch (executorType) {
                case "builtin" -> "BUILTIN";
                case "python", "node" -> "SCRIPT";
                default -> "MARKDOWN";
            };
            String sourceRef = skillPath.toString();
            String executorRef = entrypointPath == null ? skillPath.toString() : entrypointPath.toString();

            return Optional.of(new SkillBundleDefinition(
                    skillId,
                    slug,
                    name,
                    description,
                    sourceType,
                    sourceRef,
                    parsed.body().isBlank() ? markdown.strip() : parsed.body().trim(),
                    triggerKeywords,
                    triggerExamples,
                    toolPacks,
                    preferredMode,
                    contextPolicy,
                    executorType,
                    executorRef,
                    enabled,
                    priority,
                    agentVisible,
                    category,
                    tier,
                    inputHint,
                    normalizedBundlePath,
                    skillPath,
                    entrypointPath
            ));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public static Path resolveSkillPath(Path bundlePath) {
        Path upper = bundlePath.resolve(SKILL_FILE_NAME);
        if (Files.isRegularFile(upper)) {
            return upper;
        }
        Path lower = bundlePath.resolve("skill.md");
        if (Files.isRegularFile(lower)) {
            return lower;
        }
        return null;
    }

    /**
     * 解析 SKILL.md 全文，分离 frontmatter 和 body。
     * frontmatter 存储在 ParsedSkillMarkdown 的 flat Map 中。
     */
    public static ParsedSkillMarkdown parseMarkdown(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return new ParsedSkillMarkdown(new LinkedHashMap<>(), "", "");
        }
        String normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParsedSkillMarkdown(new LinkedHashMap<>(), normalized.trim(), normalized);
        }
        var matcher = FRONTMATTER_BOUNDARY.matcher(normalized);
        if (!matcher.find(4)) {
            return new ParsedSkillMarkdown(new LinkedHashMap<>(), normalized.trim(), normalized);
        }
        int frontmatterEnd = matcher.end();
        String frontmatterText = normalized.substring(4, matcher.start()).trim();
        String body = normalized.substring(frontmatterEnd).trim();
        Map<String, Object> frontmatter = parseFrontmatter(frontmatterText);
        return new ParsedSkillMarkdown(frontmatter, body, normalized);
    }

    /**
     * 解析 YAML frontmatter 文本为扁平 Map。
     * 匹配 SnakeYAML 加载结构：顶层键值对 + 嵌套 Map/List。
     */
    public static Map<String, Object> parseFrontmatter(String frontmatterText) {
        if (!StringUtils.hasText(frontmatterText)) {
            return new LinkedHashMap<>();
        }
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(frontmatterText);
        if (loaded instanceof Map<?, ?> rawMap) {
            return toStringKeyMap(rawMap);
        }
        return new LinkedHashMap<>();
    }

    /**
     * 将 frontmatter Map 渲染回 YAML 文本（用于写入 SKILL.md）。
     */
    public static String renderMarkdown(Map<String, Object> frontmatter, String body) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        Yaml yaml = new Yaml(options);
        String dumped = yaml.dump(frontmatter == null ? Map.of() : frontmatter).trim();
        String safeBody = body == null ? "" : body.strip();
        if (!StringUtils.hasText(dumped)) {
            return safeBody + "\n";
        }
        if (!StringUtils.hasText(safeBody)) {
            return "---\n" + dumped + "\n---\n";
        }
        return "---\n" + dumped + "\n---\n\n" + safeBody + "\n";
    }

    public static String sanitizeSlug(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-|-$)", "");
    }

    public static String normalizeMode(String raw) {
        String value = firstNonBlank(raw, "simplified").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "opar" -> "opar";
            default -> "simplified";
        };
    }

    // ---- 辅助方法 ----

    /** 从 Map 中读取字符串，遍历 keys 找到第一个有值的结果 */
    public static String getString(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (StringUtils.hasText(text)) return text;
            }
        }
        return null;
    }

    /** 从 Map 中读取布尔值，遍历 keys */
    private static boolean getBoolean(Map<String, Object> map, boolean defaultValue, String... keys) {
        if (map == null) return defaultValue;
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Boolean bool) return bool;
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (StringUtils.hasText(text)) return Boolean.parseBoolean(text);
            }
        }
        return defaultValue;
    }

    /** 从 Map 中读取整数，遍历 keys */
    public static int getInteger(Map<String, Object> map, int defaultValue, String... keys) {
        if (map == null) return defaultValue;
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number num) return num.intValue();
            if (value != null) {
                try { return Integer.parseInt(String.valueOf(value).trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return defaultValue;
    }

    public static List<String> parseCsv(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            if (StringUtils.hasText(token)) values.add(token.trim());
        }
        return List.copyOf(values);
    }

    public static String firstNonBlank(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) return candidate.trim();
        }
        return null;
    }

    public static List<String> mergeKeywords(String explicitCsv, String... defaults) {
        LinkedHashSet<String> values = new LinkedHashSet<>(parseCsv(explicitCsv));
        if (defaults != null) {
            for (String value : defaults) {
                if (StringUtils.hasText(value)) values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    /** 将 Object 转为 List<String>，支持 List、逗号分隔字符串、单值 */
    public static List<String> toStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> rawList) {
            LinkedHashSet<String> items = new LinkedHashSet<>();
            for (Object item : rawList) {
                String text = item == null ? null : String.valueOf(item).trim();
                if (StringUtils.hasText(text)) items.add(text);
            }
            return List.copyOf(items);
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) return List.of();
        if (text.contains(",")) return parseCsv(text);
        return List.of(text);
    }

    // ---- 内部辅助 ----

    private static Map<String, Object> toStringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), normalizeYamlValue(value)));
        return normalized;
    }

    private static Object normalizeYamlValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) return toStringKeyMap(rawMap);
        if (value instanceof List<?> rawList) {
            List<Object> result = new ArrayList<>();
            for (Object item : rawList) result.add(normalizeYamlValue(item));
            return result;
        }
        return value;
    }

    private static boolean requiresEntrypoint(String executorType) {
        return "python".equals(executorType) || "node".equals(executorType);
    }

    private static boolean isPromptExecutor(String executorType) {
        return "prompt".equals(executorType) || "markdown".equals(executorType);
    }

    private static String normalizeExecutorType(String raw) {
        if (!StringUtils.hasText(raw)) return "prompt";
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "python", "node", "builtin", "prompt" -> raw.trim().toLowerCase(Locale.ROOT);
            case "markdown" -> "prompt";
            default -> "prompt";
        };
    }

    private static String guessExecutorTypeFromEntrypoint(String entrypoint) {
        if (!StringUtils.hasText(entrypoint)) return null;
        String normalized = entrypoint.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".py")) return "python";
        if (normalized.endsWith(".js") || normalized.endsWith(".mjs") || normalized.endsWith(".cjs")) return "node";
        return null;
    }

    private static List<String> deriveDefaultKeywords(String skillId, String name) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addKeywordVariants(values, skillId);
        addKeywordVariants(values, name);
        return List.copyOf(values);
    }

    private static void addKeywordVariants(Set<String> target, String raw) {
        if (!StringUtils.hasText(raw)) return;
        String trimmed = raw.trim();
        if (trimmed.length() >= 2) target.add(trimmed);
        for (String token : trimmed.split("[\\s_\\-/]+")) {
            if (token.length() >= 2) target.add(token);
        }
    }

    private static List<String> normalizeToolPacks(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) result.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    public record ParsedSkillMarkdown(Map<String, Object> frontmatter,
                                      String body,
                                      String rawMarkdown) {
    }
}
