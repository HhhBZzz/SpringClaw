package com.springclaw.service.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownKnowledgeSourceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRenderOnlyApprovedMarkdownKnowledgeSources() throws Exception {
        Files.createDirectories(tempDir.resolve("wiki"));
        Files.writeString(tempDir.resolve("wiki/runtime.md"), """
                ---
                status: active
                source: wiki-js
                ---

                # Runtime Notes

                Tool calls need trace evidence.
                """);
        Files.writeString(tempDir.resolve("draft.md"), """
                # Draft Note

                This note should not enter project knowledge yet.
                """);
        Files.writeString(tempDir.resolve("rejected.md"), """
                ---
                status: rejected
                ---

                # Rejected Note

                Outdated architecture idea.
                """);

        MarkdownKnowledgeSourceService service = new MarkdownKnowledgeSourceService(true, tempDir.toString(), 1200, 20);

        MarkdownKnowledgeSourceService.KnowledgeSourceSnapshot snapshot = service.renderSnapshot();

        assertThat(snapshot.includedCount()).isEqualTo(1);
        assertThat(snapshot.filteredCount()).isEqualTo(2);
        assertThat(snapshot.context())
                .contains("### knowledge-source/wiki/runtime.md")
                .contains("Tool calls need trace evidence.")
                .doesNotContain("status: active")
                .doesNotContain("Draft Note")
                .doesNotContain("Rejected Note");
    }

    @Test
    void shouldReturnEmptySnapshotWhenDisabledOrMissing() {
        MarkdownKnowledgeSourceService disabled = new MarkdownKnowledgeSourceService(false, tempDir.toString(), 1200, 20);
        MarkdownKnowledgeSourceService missing = new MarkdownKnowledgeSourceService(true, tempDir.resolve("missing").toString(), 1200, 20);

        assertThat(disabled.renderSnapshot()).isEqualTo(MarkdownKnowledgeSourceService.KnowledgeSourceSnapshot.empty());
        assertThat(missing.renderSnapshot()).isEqualTo(MarkdownKnowledgeSourceService.KnowledgeSourceSnapshot.empty());
    }

    @Test
    void shouldListKnowledgeSourcesForReview() throws Exception {
        Files.writeString(tempDir.resolve("active.md"), """
                ---
                status: active
                source: wiki-js
                reviewedAt: 2026-06-17T10:00:00Z
                reviewReason: 架构师确认
                ---

                # Active Knowledge

                Runtime facts.
                """);
        Files.writeString(tempDir.resolve("draft.md"), """
                # Draft Knowledge

                Unreviewed facts.
                """);

        MarkdownKnowledgeSourceService service = new MarkdownKnowledgeSourceService(true, tempDir.toString(), 1200, 20);

        var entries = service.listSources(20);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path()).isEqualTo("active.md");
        assertThat(entries.get(0).status()).isEqualTo("active");
        assertThat(entries.get(0).source()).isEqualTo("wiki-js");
        assertThat(entries.get(0).contextIncluded()).isTrue();
        assertThat(entries.get(0).contextImpact()).isEqualTo("included_in_context");
        assertThat(entries.get(0).title()).isEqualTo("Active Knowledge");
        assertThat(entries.get(0).chars()).isPositive();
        assertThat(entries.get(0).reviewedAt()).isEqualTo("2026-06-17T10:00:00Z");
        assertThat(entries.get(0).reviewReason()).isEqualTo("架构师确认");
        assertThat(entries.get(1).path()).isEqualTo("draft.md");
        assertThat(entries.get(1).status()).isEqualTo("unreviewed");
        assertThat(entries.get(1).contextIncluded()).isFalse();
        assertThat(entries.get(1).contextImpact()).isEqualTo("filtered_from_context");
        assertThat(entries.get(1).title()).isEqualTo("Draft Knowledge");
        assertThat(entries.get(1).reviewedAt()).isBlank();
        assertThat(entries.get(1).reviewReason()).isBlank();
    }

    @Test
    void shouldRespectMaxFilesAndStablePathOrder() throws Exception {
        Files.writeString(tempDir.resolve("b.md"), """
                ---
                status: approved
                ---

                # B
                """);
        Files.writeString(tempDir.resolve("a.md"), """
                ---
                status: approved
                ---

                # A
                """);

        MarkdownKnowledgeSourceService service = new MarkdownKnowledgeSourceService(true, tempDir.toString(), 1200, 1);

        MarkdownKnowledgeSourceService.KnowledgeSourceSnapshot snapshot = service.renderSnapshot();

        assertThat(snapshot.includedCount()).isEqualTo(1);
        assertThat(snapshot.filteredCount()).isEqualTo(1);
        assertThat(snapshot.context()).contains("knowledge-source/a.md").doesNotContain("knowledge-source/b.md");
    }

    @Test
    void shouldUpdateKnowledgeSourceStatusAndFrontMatter() throws Exception {
        Files.writeString(tempDir.resolve("draft.md"), """
                # Draft Knowledge

                Runtime facts need review.
                """);
        MarkdownKnowledgeSourceService service = new MarkdownKnowledgeSourceService(true, tempDir.toString(), 1200, 20);

        var update = service.updateStatus("draft.md", "approved", "人工确认来自 Wiki.js");

        assertThat(update).isPresent();
        assertThat(update.get().path()).isEqualTo("draft.md");
        assertThat(update.get().previousStatus()).isEqualTo("unreviewed");
        assertThat(update.get().status()).isEqualTo("approved");
        assertThat(update.get().reviewedAt()).isNotBlank();
        assertThat(update.get().contextIncluded()).isTrue();
        assertThat(update.get().contextImpact()).isEqualTo("included_in_context");
        String markdown = Files.readString(tempDir.resolve("draft.md"));
        assertThat(markdown)
                .startsWith("---\n")
                .contains("status: approved")
                .contains("reviewedAt:")
                .contains("reviewReason: 人工确认来自 Wiki.js")
                .contains("# Draft Knowledge");
        assertThat(service.renderSnapshot().context()).contains("Runtime facts need review.");
    }

    @Test
    void shouldSkipKnowledgeSourceStatusUpdateWhenTargetOrStatusIsInvalid() throws Exception {
        Files.writeString(tempDir.resolve("note.md"), """
                ---
                status: active
                ---

                # Note
                """);
        MarkdownKnowledgeSourceService service = new MarkdownKnowledgeSourceService(true, tempDir.toString(), 1200, 20);
        String before = Files.readString(tempDir.resolve("note.md"));

        assertThat(service.updateStatus("../outside.md", "approved", "bad path")).isEmpty();
        assertThat(service.updateStatus("note.md", "pending", "bad status")).isEmpty();
        assertThat(service.updateStatus("missing.md", "approved", "missing")).isEmpty();
        assertThat(Files.readString(tempDir.resolve("note.md"))).isEqualTo(before);
    }
}
