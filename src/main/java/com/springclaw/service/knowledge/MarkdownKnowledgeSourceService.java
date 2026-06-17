package com.springclaw.service.knowledge;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Read-only Markdown project knowledge source for Wiki.js / Obsidian style exports.
 */
@Service
public class MarkdownKnowledgeSourceService {

    private final boolean enabled;
    private final Path rootPath;
    private final int maxChars;
    private final int maxFiles;

    public MarkdownKnowledgeSourceService(@Value("${springclaw.knowledge.source-enabled:true}") boolean enabled,
                                          @Value("${springclaw.knowledge.source-root:${user.dir}/docs/knowledge-source}") String root,
                                          @Value("${springclaw.knowledge.source-max-chars:4000}") int maxChars,
                                          @Value("${springclaw.knowledge.source-max-files:20}") int maxFiles) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxChars = Math.max(400, maxChars);
        this.maxFiles = Math.max(1, maxFiles);
    }

    public KnowledgeSourceSnapshot renderSnapshot() {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return KnowledgeSourceSnapshot.empty();
        }
        List<Path> files = markdownFiles();
        StringBuilder builder = new StringBuilder();
        int included = 0;
        int filtered = 0;
        for (Path file : files) {
            KnowledgeFile knowledgeFile = readKnowledgeFile(file);
            if (!knowledgeFile.contextIncluded() || included >= maxFiles) {
                filtered++;
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("### knowledge-source/")
                    .append(relativePath(file))
                    .append("\n")
                    .append(knowledgeFile.body())
                    .append("\n");
            included++;
        }
        return new KnowledgeSourceSnapshot(
                TextUtils.truncate(builder.toString(), maxChars).trim(),
                included,
                filtered
        );
    }

    public List<KnowledgeSourceReviewItem> listSources(int limit) {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<KnowledgeSourceReviewItem> entries = new ArrayList<>();
        for (Path file : markdownFiles()) {
            ParsedMarkdown parsed = readMarkdown(file).orElse(new ParsedMarkdown("", "", TextUtils.safe(file.getFileName().toString())));
            String status = reviewStatus(parsed.status());
            boolean contextIncluded = isContextIncludedStatus(status);
            entries.add(new KnowledgeSourceReviewItem(
                    relativePath(file),
                    status,
                    parsed.source(),
                    contextIncluded,
                    contextIncluded ? "included_in_context" : "filtered_from_context",
                    titleFor(file, parsed.body()),
                    parsed.body().length()
            ));
            if (entries.size() >= safeLimit) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    private List<Path> markdownFiles() {
        try (Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isMarkdownFile)
                    .filter(this::isWithinRoot)
                    .sorted(Comparator.comparing(this::relativePath))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private KnowledgeFile readKnowledgeFile(Path file) {
        return readMarkdown(file)
                .map(parsed -> new KnowledgeFile(isContextIncludedStatus(parsed.status()), parsed.body()))
                .orElseGet(() -> new KnowledgeFile(false, ""));
    }

    private Optional<ParsedMarkdown> readMarkdown(Path file) {
        try {
            return Optional.of(parseMarkdown(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private ParsedMarkdown parseMarkdown(String raw) {
        String text = TextUtils.safe(raw);
        if (!text.startsWith("---")) {
            return new ParsedMarkdown("", "", text.trim());
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            return new ParsedMarkdown("", "", text.trim());
        }
        List<String> frontMatter = new ArrayList<>();
        int bodyStart = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                bodyStart = i + 1;
                break;
            }
            frontMatter.add(lines[i]);
        }
        if (bodyStart < 0) {
            return new ParsedMarkdown("", "", text.trim());
        }
        String status = frontMatterValue(frontMatter, "status");
        String source = frontMatterValue(frontMatter, "source");
        String body = String.join("\n", List.of(lines).subList(bodyStart, lines.length)).trim();
        return new ParsedMarkdown(status, source, body);
    }

    private String frontMatterValue(List<String> frontMatter, String key) {
        String prefix = key + ":";
        for (String line : frontMatter) {
            String trimmed = TextUtils.safe(line).trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private boolean isContextIncludedStatus(String status) {
        String normalized = TextUtils.normalize(status);
        return normalized.equals("active") || normalized.equals("approved");
    }

    private String reviewStatus(String status) {
        String normalized = TextUtils.normalize(status);
        return normalized.isBlank() ? "unreviewed" : normalized;
    }

    private String titleFor(Path file, String body) {
        for (String line : TextUtils.safe(body).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return relativePath(file);
    }

    private boolean isMarkdownFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private boolean isWithinRoot(Path path) {
        try {
            return path.toRealPath().startsWith(rootPath.toRealPath());
        } catch (IOException ex) {
            return false;
        }
    }

    private String relativePath(Path path) {
        Path relative = rootPath.relativize(path.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/');
    }

    public record KnowledgeSourceSnapshot(String context,
                                          int includedCount,
                                          int filteredCount) {

        public KnowledgeSourceSnapshot {
            context = StringUtils.hasText(context) ? context : "";
            includedCount = Math.max(0, includedCount);
            filteredCount = Math.max(0, filteredCount);
        }

        public static KnowledgeSourceSnapshot empty() {
            return new KnowledgeSourceSnapshot("", 0, 0);
        }
    }

    public record KnowledgeSourceReviewItem(String path,
                                            String status,
                                            String source,
                                            boolean contextIncluded,
                                            String contextImpact,
                                            String title,
                                            int chars) {
    }

    private record ParsedMarkdown(String status, String source, String body) {
    }

    private record KnowledgeFile(boolean contextIncluded, String body) {
    }
}
