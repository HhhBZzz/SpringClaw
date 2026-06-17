package com.springclaw.service.knowledge;

import com.springclaw.common.util.TextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only Markdown project knowledge source for Wiki.js / Obsidian style exports.
 */
@Service
public class MarkdownKnowledgeSourceService {

    private static final Set<String> REVIEWABLE_STATUSES = Set.of(
            "active",
            "approved",
            "disabled",
            "rejected"
    );

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
            ParsedMarkdown parsed = readMarkdown(file).orElse(new ParsedMarkdown("", "", "", "", TextUtils.safe(file.getFileName().toString())));
            String status = reviewStatus(parsed.status());
            boolean contextIncluded = isContextIncludedStatus(status);
            entries.add(new KnowledgeSourceReviewItem(
                    relativePath(file),
                    status,
                    parsed.source(),
                    contextIncluded,
                    contextIncluded ? "included_in_context" : "filtered_from_context",
                    titleFor(file, parsed.body()),
                    parsed.body().length(),
                    parsed.reviewedAt(),
                    parsed.reviewReason()
            ));
            if (entries.size() >= safeLimit) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    public Optional<KnowledgeSourceStatusUpdate> updateStatus(String relativePath, String status, String reason) {
        String normalizedStatus = TextUtils.normalize(status);
        if (!enabled || !REVIEWABLE_STATUSES.contains(normalizedStatus)) {
            return Optional.empty();
        }
        Optional<Path> file = resolveKnowledgeSourceFile(relativePath);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            String existing = Files.readString(file.get(), StandardCharsets.UTF_8);
            String previousStatus = reviewStatus(parseMarkdown(existing).status());
            String reviewReason = TextUtils.truncate(TextUtils.normalizeWS(reason), 400);
            String reviewedAt = Instant.now().toString();
            Files.writeString(
                    file.get(),
                    renderStatusFrontMatter(existing, normalizedStatus, reviewReason, reviewedAt),
                    StandardCharsets.UTF_8
            );
            boolean contextIncluded = isContextIncludedStatus(normalizedStatus);
            return Optional.of(new KnowledgeSourceStatusUpdate(
                    relativePath(file.get()),
                    previousStatus,
                    normalizedStatus,
                    reviewReason,
                    reviewedAt,
                    contextIncluded,
                    contextIncluded ? "included_in_context" : "filtered_from_context"
            ));
        } catch (IOException ex) {
            return Optional.empty();
        }
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

    private Optional<Path> resolveKnowledgeSourceFile(String relativePath) {
        String value = TextUtils.normalizeWS(relativePath);
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        Path requested = Path.of(value);
        if (requested.isAbsolute()) {
            return Optional.empty();
        }
        Path file = rootPath.resolve(requested).toAbsolutePath().normalize();
        if (!file.startsWith(rootPath)
                || !Files.isRegularFile(file)
                || !isMarkdownFile(file)
                || !isWithinRoot(file)) {
            return Optional.empty();
        }
        return Optional.of(file);
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
            return new ParsedMarkdown("", "", "", "", text.trim());
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            return new ParsedMarkdown("", "", "", "", text.trim());
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
            return new ParsedMarkdown("", "", "", "", text.trim());
        }
        String status = frontMatterValue(frontMatter, "status");
        String source = frontMatterValue(frontMatter, "source");
        String reviewedAt = frontMatterValue(frontMatter, "reviewedAt");
        String reviewReason = frontMatterValue(frontMatter, "reviewReason");
        String body = String.join("\n", List.of(lines).subList(bodyStart, lines.length)).trim();
        return new ParsedMarkdown(status, source, reviewedAt, reviewReason, body);
    }

    private String renderStatusFrontMatter(String raw, String status, String reason, String reviewedAt) {
        String text = TextUtils.safe(raw);
        String[] splitLines = text.split("\\R", -1);
        List<String> lines = new ArrayList<>(List.of(splitLines));
        if (lines.size() >= 3 && "---".equals(lines.get(0).trim())) {
            int closing = closingFrontMatterLine(lines);
            if (closing > 0) {
                List<String> frontMatter = new ArrayList<>(lines.subList(1, closing));
                upsertFrontMatter(frontMatter, "status", status);
                upsertFrontMatter(frontMatter, "reviewedAt", reviewedAt);
                if (StringUtils.hasText(reason)) {
                    upsertFrontMatter(frontMatter, "reviewReason", reason);
                }
                String body = String.join("\n", lines.subList(closing + 1, lines.size()));
                return "---\n" + String.join("\n", frontMatter) + "\n---\n" + body;
            }
        }
        List<String> frontMatter = new ArrayList<>();
        frontMatter.add("status: " + status);
        frontMatter.add("reviewedAt: " + reviewedAt);
        if (StringUtils.hasText(reason)) {
            frontMatter.add("reviewReason: " + reason);
        }
        return "---\n" + String.join("\n", frontMatter) + "\n---\n\n" + text.stripLeading();
    }

    private int closingFrontMatterLine(List<String> lines) {
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private void upsertFrontMatter(List<String> frontMatter, String key, String value) {
        String prefix = key.toLowerCase(Locale.ROOT) + ":";
        for (int i = 0; i < frontMatter.size(); i++) {
            String line = TextUtils.safe(frontMatter.get(i)).trim();
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                frontMatter.set(i, key + ": " + value);
                return;
            }
        }
        frontMatter.add(key + ": " + value);
    }

    private String frontMatterValue(List<String> frontMatter, String key) {
        String prefix = key.toLowerCase(Locale.ROOT) + ":";
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
                                            int chars,
                                            String reviewedAt,
                                            String reviewReason) {
    }

    public record KnowledgeSourceStatusUpdate(String path,
                                              String previousStatus,
                                              String status,
                                              String reason,
                                              String reviewedAt,
                                              boolean contextIncluded,
                                              String contextImpact) {
    }

    private record ParsedMarkdown(String status, String source, String reviewedAt, String reviewReason, String body) {
    }

    private record KnowledgeFile(boolean contextIncluded, String body) {
    }
}
