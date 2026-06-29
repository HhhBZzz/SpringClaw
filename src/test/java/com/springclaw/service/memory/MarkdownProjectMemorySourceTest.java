package com.springclaw.service.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;
import com.springclaw.service.knowledge.MarkdownKnowledgeSourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A1 Task 8：MarkdownProjectMemorySource 把评审过的项目记忆文件
 * 映射为带类型的 ProjectMemoryItem，不施加全局字符截断。
 *
 * 守住不变量：
 *   - 文件名映射到 SourceType（project-brief→PROJECT_BRIEF 等）；
 *   - 未知 .md 文件映射到 OTHER_REVIEWED_PROJECT_MEMORY；
 *   - 非 .md 文件不读；
 *   - 每个 item 带 64 位 SHA-256 contentHash、review status、文件修改时间；
 *   - 不对内容施加全局字符上限。
 */
class MarkdownProjectMemorySourceTest {

    @TempDir
    Path tempDir;

    private static final MemoryScope SCOPE = MemoryScope.from(SessionAccessClaim.personal(
            SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
            "api", "session-1", "alice"));

    private MarkdownProjectMemorySource source() {
        return new MarkdownProjectMemorySource(tempDir);
    }

    @Test
    void returnsTypedSectionsWithoutGlobalTruncation() throws Exception {
        Files.writeString(tempDir.resolve("project-brief.md"), "# Project Brief\n\nSpringClaw 是本地 Agent Harness。");
        Files.writeString(tempDir.resolve("current-state.md"), "# Current State\n\n正在稳定 harness。");
        Files.writeString(tempDir.resolve("architecture-decisions.md"), "# ADR\n\nMemory Bank 是非 RAG 主线。");
        Files.writeString(tempDir.resolve("agent-learnings.md"), """
                # Agent Learnings

                ## approved learning

                - status: approved
                - rule: 保留 approved 经验。

                ## candidate learning

                - status: candidate
                - rule: 候选经验，未评审。
                """);
        Files.writeString(tempDir.resolve("progress.md"), "# Progress\n\n推进 Phase 3A1。");
        Files.writeString(tempDir.resolve("user-preferences.md"), "# Preferences\n\n中文优先。");
        Files.writeString(tempDir.resolve("other-notes.md"), "# Notes\n\n其他评审过的记忆。");
        Files.writeString(tempDir.resolve("random.txt"), "不应该读取");

        List<ProjectMemoryItem> items = source().read(SCOPE);

        assertThat(items)
                .extracting(ProjectMemoryItem::sourceType)
                .contains(
                        ProjectMemoryItem.SourceType.PROJECT_BRIEF,
                        ProjectMemoryItem.SourceType.CURRENT_STATE,
                        ProjectMemoryItem.SourceType.ARCHITECTURE_DECISION,
                        ProjectMemoryItem.SourceType.APPROVED_LEARNING,
                        ProjectMemoryItem.SourceType.PROGRESS,
                        ProjectMemoryItem.SourceType.USER_PREFERENCE,
                        ProjectMemoryItem.SourceType.OTHER_REVIEWED_PROJECT_MEMORY
                );
        assertThat(items).allSatisfy(item ->
                assertThat(item.contentHash()).hasSize(64)
        );
        assertThat(items).allSatisfy(item ->
                assertThat(item.updatedAt()).isNotNull()
        );
        assertThat(items).extracting(ProjectMemoryItem::content)
                .doesNotContain("不应该读取");
    }

    @Test
    void unknownMarkdownMapsToOtherReviewed() throws Exception {
        Files.writeString(tempDir.resolve("random-notes.md"), "# Random\n\n内容。");

        List<ProjectMemoryItem> items = source().read(SCOPE);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).sourceType())
                .isEqualTo(ProjectMemoryItem.SourceType.OTHER_REVIEWED_PROJECT_MEMORY);
    }

    @Test
    void approvedLearningItemsCarryApprovedStatus() throws Exception {
        Files.writeString(tempDir.resolve("agent-learnings.md"), """
                # Agent Learnings

                ## approved learning

                - status: approved
                - rule: 已批准。

                ## candidate learning

                - status: candidate
                - rule: 候选。
                """);

        List<ProjectMemoryItem> items = source().read(SCOPE).stream()
                .filter(item -> item.sourceType() == ProjectMemoryItem.SourceType.APPROVED_LEARNING)
                .toList();

        // APPROVED_LEARNING source 代表整个 agent-learnings.md 文件；
        // 其 reviewStatus 取决于文件内容中是否含 approved/active 学习条目。
        assertThat(items).hasSize(1);
        assertThat(items.get(0).reviewStatus()).isEqualTo(ProjectMemoryItem.ReviewStatus.APPROVED);
    }

    @Test
    void injectsApprovedKnowledgeSourcesAsProjectMemoryItems() throws Exception {
        Path knowledgeRoot = tempDir.resolve("knowledge-source");
        Files.createDirectories(knowledgeRoot);
        Files.writeString(knowledgeRoot.resolve("runtime.md"), """
                ---
                status: approved
                ---

                # Runtime rule
                Use canonical run id for trace correlation.
                """);
        Files.writeString(knowledgeRoot.resolve("rejected.md"), """
                ---
                status: rejected
                ---

                This must not enter runtime context.
                """);
        MarkdownKnowledgeSourceService knowledgeSource =
                new MarkdownKnowledgeSourceService(true, knowledgeRoot.toString(), 1200, 20);
        MarkdownProjectMemorySource source =
                new MarkdownProjectMemorySource(tempDir.resolve("memory-bank"), knowledgeSource);

        List<ProjectMemoryItem> items = source.read(SCOPE);

        assertThat(items).hasSize(1);
        ProjectMemoryItem item = items.get(0);
        assertThat(item.sourcePath()).isEqualTo("knowledge-source");
        assertThat(item.sourceType()).isEqualTo(ProjectMemoryItem.SourceType.KNOWLEDGE_SOURCE);
        assertThat(item.reviewStatus()).isEqualTo(ProjectMemoryItem.ReviewStatus.APPROVED);
        assertThat(item.content())
                .contains("knowledge-source/runtime.md")
                .contains("Use canonical run id for trace correlation.")
                .doesNotContain("This must not enter runtime context.");
    }

    @Test
    void emptyDirectoryReturnsEmptyList() {
        assertThat(source().read(SCOPE)).isEmpty();
    }

    @Test
    void contentHashIsStableAcrossReads() throws Exception {
        Files.writeString(tempDir.resolve("project-brief.md"), "# Brief\n\n稳定内容。");
        List<ProjectMemoryItem> first = source().read(SCOPE);
        List<ProjectMemoryItem> second = source().read(SCOPE);

        assertThat(first).hasSize(1);
        assertThat(second.get(0).contentHash()).isEqualTo(first.get(0).contentHash());
    }
}
