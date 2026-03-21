package com.openclaw.service.skill.markdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.common.exception.BusinessException;
import com.openclaw.service.skill.SkillDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受控 Markdown skill 目录。
 *
 * 目标：
 * 1. 先兼容 ClawHub 风格的 SKILL.md，不执行第三方脚本。
 * 2. 统一收敛到运行时 SkillDefinition，参与后台展示、路由和 prompt 注入。
 */
@Service
public class MarkdownSkillCatalogService {

    private static final Pattern GITHUB_BLOB_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+/[^/]+)/(blob|raw|tree)/([^/]+)/(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FRONTMATTER_BOUNDARY = Pattern.compile("(?m)^---\\s*$");
    private static final Pattern CLAWHUB_README = Pattern.compile("readme:\\\"((?:\\\\.|[^\\\\\"])*)\\\"");
    private static final int MAX_BODY_CHARS = 6000;
    private static final int DEFAULT_PRIORITY = 60;
    private static final String META_FILE = ".openclaw-skill.json";

    private final boolean enabled;
    private final Path rootPath;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MarkdownSkillCatalogService(@Value("${openclaw.skill.markdown.enabled:true}") boolean enabled,
                                       @Value("${openclaw.skill.markdown.root:${user.dir}/skills/markdown-imports}") String root,
                                       ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean enabled() {
        return enabled;
    }

    public Path rootPath() {
        return rootPath;
    }

    public List<SkillDefinition> listDefinitions() {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return List.of();
        }
        try (var stream = Files.list(rootPath)) {
            return stream.filter(Files::isDirectory)
                    .map(this::toDefinition)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingInt(SkillDefinition::priority)
                            .thenComparing(definition -> definition.skillId().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public Optional<SkillDefinition> matchDefinition(String question, Set<String> allowedToolPacks) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }
        String normalized = normalize(question);
        return listDefinitions().stream()
                .filter(SkillDefinition::enabled)
                .filter(SkillDefinition::agentVisible)
                .filter(definition -> definition.matchesAllowedToolPacks(allowedToolPacks))
                .map(definition -> Map.entry(definition, score(definition, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<Map.Entry<SkillDefinition, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public ImportedSkillImportResult importFromUrl(ImportMarkdownSkillRequest request) {
        if (!enabled) {
            throw new BusinessException(40105, "Markdown skill 导入未开启");
        }
        if (request == null || !StringUtils.hasText(request.url())) {
            throw new BusinessException(40106, "url 不能为空");
        }

        String normalizedUrl = normalizeSourceUrl(request.url().trim());
        String markdown = fetchMarkdown(normalizedUrl);
        ParsedSkillMd parsed = parseSkillMd(markdown);

        String slug = sanitizeSlug(firstNonBlank(
                request.slug(),
                parsed.skillKey(),
                parsed.name(),
                guessSlugFromUrl(normalizedUrl)
        ));
        if (!StringUtils.hasText(slug)) {
            throw new BusinessException(40107, "无法从来源解析 skill slug");
        }

        String displayName = firstNonBlank(request.name(), parsed.name(), slug);
        String description = firstNonBlank(request.description(), parsed.description(), displayName + " skill");
        List<String> triggerKeywords = mergeKeywords(request.triggerKeywords(), displayName, slug);
        List<String> toolPacks = parseCsv(request.toolPacks());
        String preferredMode = normalizeMode(request.preferredMode());
        String contextPolicy = firstNonBlank(request.contextPolicy(), "session-only");
        boolean agentVisible = request.agentVisible() == null || request.agentVisible();
        int priority = request.priority() == null ? DEFAULT_PRIORITY : Math.max(0, request.priority());

        Path skillDir = rootPath.resolve(slug);
        Path skillPath = skillDir.resolve("SKILL.md");
        Path metaPath = skillDir.resolve(META_FILE);
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillPath, markdown.strip() + "\n", StandardCharsets.UTF_8);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), new StoredMarkdownSkill(
                    slug,
                    displayName,
                    description,
                    normalizedUrl,
                    preferredMode,
                    contextPolicy,
                    toolPacks,
                    triggerKeywords,
                    agentVisible,
                    priority,
                    LocalDateTime.now().toString()
            ));
        } catch (IOException ex) {
            throw new BusinessException(50021, "写入 Markdown skill 失败: " + ex.getMessage());
        }

        SkillDefinition definition = toDefinition(skillDir)
                .orElseThrow(() -> new BusinessException(50022, "导入后的 Markdown skill 无法解析"));
        return new ImportedSkillImportResult(slug, normalizedUrl, skillPath.toString(), definition);
    }

    private Optional<SkillDefinition> toDefinition(Path skillDir) {
        Path skillPath = resolveSkillPath(skillDir);
        Path metaPath = skillDir.resolve(META_FILE);
        if (skillPath == null || !Files.isRegularFile(metaPath)) {
            return Optional.empty();
        }
        try {
            String markdown = Files.readString(skillPath);
            ParsedSkillMd parsed = parseSkillMd(markdown);
            StoredMarkdownSkill meta = objectMapper.readValue(metaPath.toFile(), StoredMarkdownSkill.class);
            String displayName = firstNonBlank(meta.name(), parsed.name(), meta.slug());
            String description = firstNonBlank(meta.description(), parsed.description(), "Imported Markdown skill");
            String instructions = buildInstructions(markdown, parsed.body());
            return Optional.of(new SkillDefinition(
                    meta.slug(),
                    displayName,
                    description,
                    "MARKDOWN",
                    meta.sourceUrl(),
                    instructions,
                    meta.triggerKeywords() == null ? List.of() : meta.triggerKeywords(),
                    List.of(displayName + "：" + description),
                    meta.toolPacks() == null ? List.of() : meta.toolPacks(),
                    normalizeMode(meta.preferredMode()),
                    firstNonBlank(meta.contextPolicy(), "session-only"),
                    "markdown",
                    skillPath.toString(),
                    true,
                    meta.priority() == null ? DEFAULT_PRIORITY : meta.priority(),
                    meta.agentVisible() == null || meta.agentVisible()
            ));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private String fetchMarkdown(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "OpenClaw-Java MarkdownSkillImporter")
                .header("Accept", MediaType.TEXT_PLAIN_VALUE + ", text/markdown, */*")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != HttpStatus.OK.value()) {
                throw new BusinessException(40108, "下载 Markdown skill 失败，status=" + response.statusCode());
            }
            String body = response.body();
            String extractedReadme = tryExtractClawhubReadme(body);
            if (StringUtils.hasText(extractedReadme)) {
                return extractedReadme;
            }
            if (!StringUtils.hasText(body)) {
                throw new BusinessException(40109, "下载到的 Markdown skill 为空");
            }
            return body;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(40110, "下载 Markdown skill 被中断: " + ex.getMessage());
        } catch (IOException ex) {
            throw new BusinessException(40110, "下载 Markdown skill 失败: " + ex.getMessage());
        }
    }

    private String normalizeSourceUrl(String rawUrl) {
        String trimmed = rawUrl.trim();
        Matcher matcher = GITHUB_BLOB_URL.matcher(trimmed);
        if (matcher.matches()) {
            String repo = matcher.group(1);
            String mode = matcher.group(2).toLowerCase(Locale.ROOT);
            String ref = matcher.group(3);
            String path = matcher.group(4);
            if ("tree".equals(mode) && !path.toLowerCase(Locale.ROOT).endsWith("/skill.md")
                    && !path.toLowerCase(Locale.ROOT).endsWith("/skill.md/")
                    && !path.toLowerCase(Locale.ROOT).endsWith("/skill.md?")) {
                path = path.replaceAll("/+$", "") + "/SKILL.md";
            }
            return "https://raw.githubusercontent.com/%s/%s/%s".formatted(repo, ref, path);
        }
        return trimmed;
    }

    private ParsedSkillMd parseSkillMd(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return new ParsedSkillMd(null, null, null, markdown, markdown);
        }
        String normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParsedSkillMd(null, null, null, normalized, normalized);
        }
        Matcher matcher = FRONTMATTER_BOUNDARY.matcher(normalized);
        if (!matcher.find()) {
            return new ParsedSkillMd(null, null, null, normalized, normalized);
        }
        if (!matcher.find()) {
            return new ParsedSkillMd(null, null, null, normalized, normalized);
        }
        int frontmatterEnd = matcher.end();
        String frontmatter = normalized.substring(4, matcher.start()).trim();
        String body = normalized.substring(frontmatterEnd).trim();
        String name = null;
        String description = null;
        String skillKey = null;
        for (String rawLine : frontmatter.split("\n")) {
            String line = rawLine.stripTrailing();
            if (!StringUtils.hasText(line) || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("name:")) {
                name = trimYamlValue(line.substring("name:".length()));
            } else if (line.startsWith("description:")) {
                description = trimYamlValue(line.substring("description:".length()));
            } else if (line.startsWith("skillKey:")) {
                skillKey = trimYamlValue(line.substring("skillKey:".length()));
            }
        }
        return new ParsedSkillMd(name, description, skillKey, body, normalized);
    }

    private String buildInstructions(String markdown, String body) {
        String effective = StringUtils.hasText(body) ? body : markdown;
        String trimmed = effective.trim();
        if (trimmed.length() <= MAX_BODY_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_BODY_CHARS) + "\n\n[内容已截断，保留前 " + MAX_BODY_CHARS + " 字符]";
    }

    private String tryExtractClawhubReadme(String responseBody) {
        if (!StringUtils.hasText(responseBody) || !responseBody.contains("readme:\"")) {
            return null;
        }
        Matcher matcher = CLAWHUB_README.matcher(responseBody);
        if (!matcher.find()) {
            return null;
        }
        String rawEncoded = matcher.group(1);
        try {
            return objectMapper.readValue("\"" + rawEncoded + "\"", String.class);
        } catch (IOException ex) {
            return rawEncoded
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\");
        }
    }

    private Path resolveSkillPath(Path skillDir) {
        Path upper = skillDir.resolve("SKILL.md");
        if (Files.isRegularFile(upper)) {
            return upper;
        }
        Path lower = skillDir.resolve("skill.md");
        return Files.isRegularFile(lower) ? lower : null;
    }

    private String sanitizeSlug(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = normalize(raw)
                .replace('_', '-')
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return normalized;
    }

    private String guessSlugFromUrl(String sourceUrl) {
        try {
            String path = new URI(sourceUrl).getPath();
            if (!StringUtils.hasText(path)) {
                return "";
            }
            String fileName = Path.of(path).getFileName().toString();
            if ("SKILL.md".equalsIgnoreCase(fileName) || "skill.md".equalsIgnoreCase(fileName)) {
                Path parent = Path.of(path).getParent();
                return parent == null ? "" : parent.getFileName().toString();
            }
            return fileName.replaceFirst("\\.[^.]+$", "");
        } catch (URISyntaxException ex) {
            return "";
        }
    }

    private int score(SkillDefinition definition, String normalizedQuestion) {
        int score = 0;
        for (String keyword : definition.triggerKeywords()) {
            if (normalizedQuestion.contains(normalize(keyword))) {
                score += 2;
            }
        }
        for (String token : deriveTokens(definition)) {
            if (normalizedQuestion.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private Set<String> deriveTokens(SkillDefinition definition) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addMeaningfulTokens(tokens, definition.skillId());
        addMeaningfulTokens(tokens, definition.name());
        return tokens;
    }

    private void addMeaningfulTokens(Set<String> tokens, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        for (String token : raw.toLowerCase(Locale.ROOT).split("[\\s_\\-/]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        String normalized = normalize(raw);
        if (normalized.length() >= 2) {
            tokens.add(normalized);
        }
    }

    private List<String> mergeKeywords(String rawKeywords, String displayName, String slug) {
        LinkedHashSet<String> result = new LinkedHashSet<>(parseCsv(rawKeywords));
        if (StringUtils.hasText(displayName)) {
            result.add(displayName.trim());
        }
        if (StringUtils.hasText(slug)) {
            result.add(slug.trim());
            for (String token : slug.split("-")) {
                if (token.length() >= 3) {
                    result.add(token);
                }
            }
        }
        return result.stream().filter(StringUtils::hasText).limit(12).toList();
    }

    private List<String> parseCsv(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String normalized = item == null ? "" : item.trim();
            if (StringUtils.hasText(normalized)) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "simplified";
        }
        return "opar".equalsIgnoreCase(mode.trim()) ? "opar" : "simplified";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimYamlValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return "";
    }

    public record ImportMarkdownSkillRequest(String url,
                                             String slug,
                                             String name,
                                             String description,
                                             String triggerKeywords,
                                             String toolPacks,
                                             String preferredMode,
                                             String contextPolicy,
                                             Boolean agentVisible,
                                             Integer priority) {
    }

    public record ImportedSkillImportResult(String slug,
                                            String sourceUrl,
                                            String skillPath,
                                            SkillDefinition definition) {
    }

    private record ParsedSkillMd(String name,
                                 String description,
                                 String skillKey,
                                 String body,
                                 String rawMarkdown) {
    }

    private record StoredMarkdownSkill(String slug,
                                       String name,
                                       String description,
                                       String sourceUrl,
                                       String preferredMode,
                                       String contextPolicy,
                                       List<String> toolPacks,
                                       List<String> triggerKeywords,
                                       Boolean agentVisible,
                                       Integer priority,
                                       String importedAt) {
    }
}
