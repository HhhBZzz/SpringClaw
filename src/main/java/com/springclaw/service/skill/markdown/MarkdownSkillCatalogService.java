package com.springclaw.service.skill.markdown;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillBundleDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.bundle.SkillBundleSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * package markdown skill 目录与受控导入。
 */
@Service
public class MarkdownSkillCatalogService {

    private static final Pattern GITHUB_BLOB_URL = Pattern.compile(
            "^https?://github\\.com/([^/]+/[^/]+)/(blob|raw|tree)/([^/]+)/(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final int DEFAULT_PRIORITY = 60;

    private final boolean enabled;
    private final Path rootPath;
    private final HttpClient httpClient;
    private final SkillCatalogService skillCatalogService;

    public MarkdownSkillCatalogService(boolean enabled,
                                       String root,
                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(enabled, root, new SkillCatalogService(enabled, root));
    }

    @Autowired
    public MarkdownSkillCatalogService(@Value("${springclaw.skills.markdown-enabled:true}") boolean enabled,
                                       @Value("${springclaw.skills.root:${user.dir}/skills}") String root,
                                       SkillCatalogService skillCatalogService) {
        this(enabled, root, skillCatalogService, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    MarkdownSkillCatalogService(boolean enabled,
                                String root,
                                SkillCatalogService skillCatalogService,
                                HttpClient httpClient) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.skillCatalogService = skillCatalogService;
        this.httpClient = httpClient;
    }

    public boolean enabled() {
        return enabled;
    }

    public Path rootPath() {
        return rootPath;
    }

    public List<SkillDefinition> listDefinitions() {
        if (!enabled) {
            return List.of();
        }
        return skillCatalogService.listBundles().stream()
                .filter(this::isPromptBundle)
                .map(SkillBundleDefinition::toRuntimeDefinition)
                .sorted(Comparator.comparingInt(SkillDefinition::priority)
                        .thenComparing(definition -> definition.skillId().toLowerCase(Locale.ROOT)))
                .toList();
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
        SkillBundleSupport.ParsedSkillMarkdown parsed = SkillBundleSupport.parseMarkdown(markdown);
        Map<String, Object> frontmatter = new LinkedHashMap<>(parsed.frontmatter());

        String slug = SkillBundleSupport.sanitizeSlug(SkillBundleSupport.firstNonBlank(
                request.slug(),
                SkillBundleSupport.getString(frontmatter, "skillId"),
                SkillBundleSupport.getString(frontmatter, "name"),
                guessSlugFromUrl(normalizedUrl)
        ));
        if (!StringUtils.hasText(slug)) {
            throw new BusinessException(40107, "无法从来源解析 skill slug");
        }

        String displayName = SkillBundleSupport.firstNonBlank(request.name(), SkillBundleSupport.getString(frontmatter, "name"), slug);
        String description = SkillBundleSupport.firstNonBlank(request.description(), SkillBundleSupport.getString(frontmatter, "description"), displayName + " skill");
        frontmatter.put("displayName", displayName);
        frontmatter.put("description", description);

        // 扁平 frontmatter：所有字段直接写入顶层
        frontmatter.put("skillId", slug);
        frontmatter.put("type", "prompt");
        frontmatter.put("toolPacks", chooseToolPacks(request.toolPacks(), frontmatter.get("toolPacks")));
        frontmatter.put("preferredMode", SkillBundleSupport.normalizeMode(
                SkillBundleSupport.firstNonBlank(request.preferredMode(), SkillBundleSupport.getString(frontmatter, "preferredMode"), "simplified")
        ));
        frontmatter.put("contextPolicy", SkillBundleSupport.firstNonBlank(request.contextPolicy(), SkillBundleSupport.getString(frontmatter, "contextPolicy"), "session-only"));
        frontmatter.put("agentVisible", request.agentVisible() == null || request.agentVisible());
        frontmatter.put("priority", request.priority() == null ? SkillBundleSupport.getInteger(frontmatter, DEFAULT_PRIORITY, "priority") : Math.max(0, request.priority()));
        frontmatter.put("triggerKeywords", chooseTriggerKeywords(request.triggerKeywords(), frontmatter.get("triggerKeywords"), displayName, slug));

        String body = parsed.body();
        if (!StringUtils.hasText(body)) {
            body = markdown.strip();
        }

        Path skillDir = rootPath.resolve(slug);
        Path skillPath = skillDir.resolve(SkillBundleSupport.SKILL_FILE_NAME);
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillPath, SkillBundleSupport.renderMarkdown(frontmatter, body), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException(50021, "写入 Markdown skill 失败: " + ex.getMessage());
        }

        SkillDefinition definition = SkillBundleSupport.parseBundle(skillDir)
                .filter(this::isPromptBundle)
                .map(SkillBundleDefinition::toRuntimeDefinition)
                .orElseThrow(() -> new BusinessException(50022, "导入后的 Markdown skill 无法解析"));
        return new ImportedSkillImportResult(slug, normalizedUrl, skillPath.toString(), definition);
    }

    private boolean isPromptBundle(SkillBundleDefinition definition) {
        return "prompt".equalsIgnoreCase(definition.executorType())
                || "markdown".equalsIgnoreCase(definition.executorType());
    }

    private String fetchMarkdown(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SpringClaw-Java MarkdownSkillImporter")
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

    private List<String> chooseToolPacks(String explicitCsv, Object existingValue) {
        List<String> explicit = SkillBundleSupport.parseCsv(explicitCsv);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return SkillBundleSupport.toStringList(existingValue);
    }

    private List<String> chooseTriggerKeywords(String explicitCsv, Object existingValue, String displayName, String slug) {
        List<String> explicit = SkillBundleSupport.parseCsv(explicitCsv);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        List<String> existing = SkillBundleSupport.toStringList(existingValue);
        if (!existing.isEmpty()) {
            return existing;
        }
        return SkillBundleSupport.mergeKeywords(null, displayName, slug);
    }

    private String normalizeSourceUrl(String rawUrl) {
        String trimmed = rawUrl.trim();
        Matcher matcher = GITHUB_BLOB_URL.matcher(trimmed);
        if (matcher.matches()) {
            String repo = matcher.group(1);
            String mode = matcher.group(2).toLowerCase(Locale.ROOT);
            String ref = matcher.group(3);
            String path = matcher.group(4);
            if ("tree".equals(mode)
                    && !path.toLowerCase(Locale.ROOT).endsWith("/skill.md")
                    && !path.toLowerCase(Locale.ROOT).endsWith("/skill.md/")
                    && !path.toLowerCase(Locale.ROOT).endsWith("/skill.md?")) {
                path = path.replaceAll("/+$", "") + "/SKILL.md";
            }
            return "https://raw.githubusercontent.com/%s/%s/%s".formatted(repo, ref, path);
        }
        return trimmed;
    }

    private String guessSlugFromUrl(String url) {
        String trimmed = url.trim();
        int hashIndex = trimmed.lastIndexOf('#');
        if (hashIndex >= 0) {
            trimmed = trimmed.substring(0, hashIndex);
        }
        int queryIndex = trimmed.lastIndexOf('?');
        if (queryIndex >= 0) {
            trimmed = trimmed.substring(0, queryIndex);
        }
        String[] tokens = trimmed.split("/");
        if (tokens.length == 0) {
            return "";
        }
        String last = tokens[tokens.length - 1];
        if ("SKILL.md".equalsIgnoreCase(last) || "skill.md".equalsIgnoreCase(last)) {
            if (tokens.length >= 2) {
                return tokens[tokens.length - 2];
            }
            return "skill";
        }
        return last;
    }

    private String tryExtractClawhubReadme(String html) {
        if (!StringUtils.hasText(html)) {
            return null;
        }
        String marker = "readme:\\\"";
        int markerIndex = html.indexOf(marker);
        if (markerIndex < 0) {
            marker = "readme:\"";
            markerIndex = html.indexOf(marker);
        }
        if (markerIndex < 0) {
            return null;
        }
        int start = markerIndex + marker.length();
        StringBuilder escaped = new StringBuilder();
        boolean escaping = false;
        for (int index = start; index < html.length(); index++) {
            char ch = html.charAt(index);
            if (escaping) {
                escaped.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaped.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                break;
            }
            escaped.append(ch);
        }
        String escapedText = escaped.toString();
        return escapedText
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\\"", "\"")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .trim();
    }

    private int score(SkillDefinition definition, String normalizedQuestion) {
        int score = 0;
        if (definition.triggerKeywords() != null) {
            for (String keyword : definition.triggerKeywords()) {
                if (StringUtils.hasText(keyword) && normalizedQuestion.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                    score += 3;
                }
            }
        }
        if (normalizedQuestion.contains(definition.skillId().toLowerCase(Locale.ROOT))) {
            score += 1;
        }
        if (normalizedQuestion.contains(definition.name().toLowerCase(Locale.ROOT))) {
            score += 1;
        }
        return score;
    }

    private String normalize(String question) {
        return question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
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
}
