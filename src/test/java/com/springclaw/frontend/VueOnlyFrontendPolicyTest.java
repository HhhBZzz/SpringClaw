package com.springclaw.frontend;

import com.springclaw.controller.HomeController;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VueOnlyFrontendPolicyTest {

    private final Path projectRoot = Path.of(System.getProperty("project.root", "")).toAbsolutePath().normalize();

    @Test
    void shouldNotKeepLegacySpringBootStaticFrontendPages() {
        assertThat(projectRoot.resolve("src/main/resources/static/agent/index.html"))
                .doesNotExist();
        assertThat(projectRoot.resolve("src/main/resources/static/admin/index.html"))
                .doesNotExist();
    }

    @Test
    void homeControllerShouldOnlyExposeVueFrontendEntries() {
        Map<String, Object> data = new HomeController().home().getData();

        assertThat(data).containsEntry("frontend", "Vue 3 + Vite");
        assertThat(data).doesNotContainKeys("staticAgentPage", "adminPage");
        assertThat(data.values()).doesNotContain("/agent/index.html", "/admin/index.html");
    }

    @Test
    void agentConsoleShouldKeepEngineerRuntimePreviewStructure() throws IOException {
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String styles = Files.readString(projectRoot.resolve("frontend/src/assets/styles.css"));

        assertThat(agentView)
                .contains("class=\"runtime-app-sidebar\"")
                .contains("class=\"runtime-worktop\"")
                .contains("SpringClaw AI Runtime")
                .contains("New Session")
                .contains("class=\"runtime-quick-link\"")
                .contains("class=\"runtime-notification-button\"")
                .contains("{{ auth.username || 'Guest' }}")
                .contains("Use authorized local files")
                .contains("nav-console")
                .contains("@click=\"activateRuntimeNav(item.key)\"")
                .contains("@click=\"toggleSessionSearch\"")
                .contains("@click=\"toggleRuntimeSidebar\"")
                .contains("@click=\"openNotifications\"")
                .contains("@click=\"renameCurrentSession\"")
                .contains("@click=\"toggleTaskMeta\"")
                .contains("@click=\"startAttachFlow\"");

        assertThat(styles)
                .contains(".runtime-app-sidebar")
                .contains(".runtime-worktop")
                .contains(".runtime-brand-glyph")
                .contains(".runtime-nav-item");
    }

    @Test
    void agentConsoleRuntimeNavShouldOpenRealResourcePanelsInsteadOfPromptShortcuts() throws IOException {
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String api = Files.readString(projectRoot.resolve("frontend/src/services/api.ts"));
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));

        assertThat(agentView)
                .contains("RuntimeResourceView")
                .contains("loadRuntimeResource")
                .contains("runtime-resource-panel")
                .contains("runtime-skill-grid")
                .contains("runtime-task-list")
                .contains("runtime-usage-grid")
                .contains("openModelSwitcher")
                .doesNotContain("已填充 Agents 架构说明 Prompt")
                .doesNotContain("已切到 Tool Calls，并填充 Skills 盘点 Prompt")
                .doesNotContain("已切到 Tool Calls，并填充 Tools 盘点 Prompt");

        assertThat(api)
                .contains("getRuntimeOverview")
                .contains("getRuntimeSkills")
                .contains("getRuntimeTools")
                .contains("getRuntimeTasks")
                .contains("getRuntimeUsage")
                .contains("getRuntimeModelProviders")
                .contains("switchRuntimeModelProvider");

        assertThat(types)
                .contains("RuntimeOverview")
                .contains("RuntimeSkill")
                .contains("RuntimeTool")
                .contains("RuntimeTask")
                .contains("RuntimeUsageSummary")
                .contains("RuntimeModelProviders");
    }

    @Test
    void runtimeConsoleShouldExposeLearningReviewPanel() throws IOException {
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String api = Files.readString(projectRoot.resolve("frontend/src/services/api.ts"));
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));

        assertThat(agentView)
                .contains("nav-learning")
                .contains("runtimeLearningItems")
                .contains("learning-review-list")
                .contains("learning-review-counterexample")
                .contains("learning-review-category")
                .contains("learning-review-impact")
                .contains("learning-review-evidence")
                .contains("learning-review-reason")
                .contains("learning-review-filters")
                .contains("learningReviewReasons")
                .contains("learningReviewStatusFilter")
                .contains("learningReviewFilterOptions")
                .contains("filteredRuntimeLearningItems")
                .contains("Counterexample")
                .contains("Evidence")
                .contains("Counterexample type")
                .contains("Context impact")
                .contains("Review reason")
                .contains("item.counterexampleCategory")
                .contains("item.contextImpact")
                .contains("Influencing rules")
                .contains("loaded review items")
                .contains("All")
                .contains("Superseded")
                .contains("@click=\"learningReviewStatusFilter = option.value\"")
                .contains("v-for=\"item in filteredRuntimeLearningItems\"")
                .contains("@click=\"reviewLearningItem(item, 'approved')\"")
                .contains("@click=\"reviewLearningItem(item, 'active')\"")
                .contains("@click=\"reviewLearningItem(item, 'disabled')\"")
                .contains("@click=\"reviewLearningItem(item, 'rejected')\"")
                .contains("@click=\"reviewLearningItem(item, 'superseded')\"");

        assertThat(api)
                .contains("getRuntimeLearningEntries")
                .contains("updateRuntimeLearningStatus")
                .contains("/api/runtime-console/learning")
                .contains("/api/runtime-console/learning/status");

        assertThat(types)
                .contains("RuntimeLearningReviewItem")
                .contains("counterexampleCategory?: string")
                .contains("contextIncluded?: boolean")
                .contains("contextImpact?: string")
                .contains("RuntimeLearningStatusUpdate");
    }

    @Test
    void runtimeConsoleShouldExposeKnowledgeSourceReviewPanel() throws IOException {
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String api = Files.readString(projectRoot.resolve("frontend/src/services/api.ts"));
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));

        assertThat(agentView)
                .contains("nav-knowledge")
                .contains("runtimeKnowledgeSources")
                .contains("knowledge-source-list")
                .contains("knowledge-source-card")
                .contains("knowledge-source-impact")
                .contains("knowledge-source-snapshot")
                .contains("Knowledge Source")
                .contains("Project knowledge")
                .contains("not injected into runtime prompt yet")
                .contains("runtimeKnowledgeSnapshot")
                .contains("injectedToRuntimePrompt")
                .contains("knowledgeReviewReasons")
                .contains("knowledgeReviewPendingPath")
                .contains("reviewKnowledgeSource")
                .contains("knowledge-source-status-actions")
                .contains("item.reviewReason || item.reviewedAt")
                .contains("@click=\"reviewKnowledgeSource(item, 'approved')\"")
                .contains("@click=\"reviewKnowledgeSource(item, 'disabled')\"")
                .contains("@click=\"reviewKnowledgeSource(item, 'rejected')\"")
                .contains("included_in_context")
                .contains("filtered_from_context");

        assertThat(api)
                .contains("getRuntimeKnowledgeSources")
                .contains("getRuntimeKnowledgeSourceSnapshot")
                .contains("updateRuntimeKnowledgeSourceStatus")
                .contains("/api/runtime-console/knowledge-sources")
                .contains("/api/runtime-console/knowledge-sources/snapshot")
                .contains("/api/runtime-console/knowledge-sources/status");

        assertThat(types)
                .contains("'knowledge'")
                .contains("RuntimeKnowledgeSourceReviewStatus")
                .contains("RuntimeKnowledgeSourceReviewItem")
                .contains("RuntimeKnowledgeSourceSnapshot")
                .contains("RuntimeKnowledgeSourceStatusUpdate")
                .contains("contextIncluded: boolean")
                .contains("contextImpact: string")
                .contains("reviewedAt?: string")
                .contains("reviewReason?: string")
                .contains("injectedToRuntimePrompt: boolean")
                .contains("contextPolicy: string");
    }

    @Test
    void runtimeConsoleShouldExposeMemoryCandidateReviewPanelUsingMemoryRecordContract() throws IOException {
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String api = Files.readString(projectRoot.resolve("frontend/src/services/api.ts"));
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));

        assertThat(agentView)
                .contains("nav-memory-candidates")
                .contains("runtimeMemoryCandidates")
                .contains("Memory Candidates")
                .contains("memory-candidate-list")
                .contains("memory-candidate-card")
                .contains("memoryCandidateReviewReasons")
                .contains("memoryCandidateReviewPendingVersionId")
                .contains("let memoryCandidateLoadSeq = 0")
                .contains("reviewMemoryCandidate")
                .contains("clearRuntimeMemoryCandidates")
                .contains("memoryCandidateLoadSeq += 1;")
                .contains("const loadSeq = ++memoryCandidateLoadSeq;")
                .contains("if (loadSeq !== memoryCandidateLoadSeq) return null;\n    runtimeMemoryCandidates.value = candidates;")
                .contains("if (loadSeq === memoryCandidateLoadSeq) {\n      clearRuntimeMemoryCandidates();\n      throw error;\n    }\n    return null;")
                .contains("if (refreshedCandidates === null) return;")
                .contains("getRuntimeMemoryCandidates")
                .contains("updateRuntimeMemoryCandidateStatus")
                .contains("const refreshedCandidates = await refreshRuntimeMemoryCandidates();\n    runtimeResourceError.value = '';")
                .contains("item.memoryVersionId")
                .contains("item.memoryType")
                .contains("item.content")
                .contains("item.summary")
                .contains("item.ownerUserId")
                .contains("item.importance")
                .contains("item.confidence")
                .contains("item.evidenceRefs")
                .contains("item.tags")
                .contains("item.sourceKind")
                .contains("item.sourceIdentity")
                .contains("item.extractionPolicyVersion")
                .contains("item.updatedAt")
                .contains("@click=\"reviewMemoryCandidate(item, 'ACTIVE')\"")
                .contains("@click=\"reviewMemoryCandidate(item, 'REJECTED')\"")
                .contains("@click=\"reviewMemoryCandidate(item, 'EXPIRED')\"")
                .contains("@click=\"reviewMemoryCandidate(item, 'SUPERSEDED')\"")
                .doesNotContain("reviewMemoryCandidate(item, 'approved')")
                .doesNotContain("memoryCandidateReviewReasons[item.signature]")
                .doesNotContain("if (view === 'memory-candidates') {\n      clearRuntimeMemoryCandidates();\n    }")
                .doesNotContain("const candidates = await getRuntimeMemoryCandidates('CANDIDATE', 50);\n    runtimeMemoryCandidates.value = candidates;")
                .doesNotContain("runtimeMemoryCandidates.value = await getRuntimeMemoryCandidates(50);")
                .doesNotContain("Memory Candidate ${item.signature");

        assertThat(api)
                .contains("getRuntimeMemoryCandidates")
                .contains("updateRuntimeMemoryCandidateStatus")
                .contains("/api/runtime-console/memory/candidates?status=${encodeURIComponent(status)}")
                .contains("status: RuntimeMemoryCandidateListStatus = 'CANDIDATE'")
                .contains("/api/runtime-console/memory/candidates/status")
                .contains("memoryVersionId")
                .contains("status")
                .doesNotContain("/api/runtime-console/memory-candidates")
                .doesNotContain("updateRuntimeMemoryCandidateStatus(signature")
                .doesNotContain("RuntimeMemoryCandidateStatusUpdate>('/api/runtime-console/memory/candidates/status', {\n    method: 'POST',\n    body: JSON.stringify({ signature");

        assertThat(types)
                .contains("'memory-candidates'")
                .contains("RuntimeMemoryCandidateReviewItem")
                .contains("RuntimeMemoryCandidateReviewStatus")
                .contains("RuntimeMemoryCandidateStatusUpdate")
                .contains("memoryVersionId: string")
                .contains("evidenceRefs: string[]")
                .contains("tags: string[]")
                .contains("'ACTIVE' | 'REJECTED' | 'EXPIRED' | 'SUPERSEDED'")
                .doesNotContain("extends RuntimeLearningReviewItem")
                .doesNotContain("RuntimeMemoryCandidateReviewStatus = 'approved'");
    }

    @Test
    void runtimeConsoleShouldExposeToolProposalAuditPanel() throws IOException {
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String api = Files.readString(projectRoot.resolve("frontend/src/services/api.ts"));
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));

        assertThat(agentView)
                .contains("nav-proposals")
                .contains("runtimeToolProposals")
                .contains("tool-proposal-list")
                .contains("tool-proposal-card")
                .contains("Tool Proposals")
                .contains("workspaceDirtyAtCreate")
                .contains("gitCommitSha")
                .contains("executionError")
                .contains("getToolProposals")
                .contains("toolProposalScopeFilter")
                .contains("toolProposalStatusFilter")
                .contains("toolProposalStatusFilterOptions")
                .contains("toolProposalLoadSeq")
                .contains("if (loadSeq !== toolProposalLoadSeq) return")
                .contains("setToolProposalScope")
                .contains("setToolProposalStatusFilter")
                .contains("tool-proposal-toolbar")
                .contains("tool-proposal-scope-toggle")
                .contains("tool-proposal-status-filters")
                .contains("Current Session")
                .contains("All Visible")
                .contains("Selected confirmation audit")
                .contains("selected results")
                .contains(":aria-pressed=\"toolProposalStatusFilter === option.value\"")
                .contains("FAILED")
                .contains("REJECTED")
                .contains("toolProposalStatusClass")
                .contains("status: toolProposalStatusFilter.value === 'all' ? undefined : toolProposalStatusFilter.value")
                .contains("sessionKey: toolProposalScopeFilter.value === 'current-session' ? sessionKey.value : undefined")
                .contains("activeResourceView === 'proposals'");

        assertThat(api)
                .contains("getToolProposals")
                .contains("RuntimeToolProposal")
                .contains("/api/tool-proposals")
                .contains("sessionKey")
                .contains("status");

        assertThat(types)
                .contains("'proposals'")
                .contains("RuntimeToolProposal")
                .contains("workspaceDirtyAtCreate?: boolean")
                .contains("gitCommitSha?: string")
                .contains("executionError?: string")
                .contains("dirtyFilesAtCreate?: string[]")
                .contains("gitChangedFiles?: string[]");

        String styles = Files.readString(projectRoot.resolve("frontend/src/assets/styles.css"));
        assertThat(styles)
                .contains(".tool-proposal-card em.status-failed")
                .contains(".tool-proposal-card em.status-rejected")
                .contains(".tool-proposal-card em.status-executed")
                .contains(".tool-proposal-card .runtime-resource-error");
    }

    @Test
    void adminRouteShouldRemainReachableForUnauthenticatedLogin() throws IOException {
        String router = Files.readString(projectRoot.resolve("frontend/src/router/index.ts"));
        String adminView = Files.readString(projectRoot.resolve("frontend/src/views/AdminView.vue"));

        assertThat(router)
                .contains("{ path: '/admin', name: 'admin', component: AdminView }")
                .doesNotContain("{ path: '/admin', name: 'admin', component: AdminView, meta: { requiresAuth: true, requiresAdmin: true } }");
        assertThat(adminView)
                .contains("<LoginPanel />")
                .contains("需要 ADMIN 角色才能查看后台数据");
    }

    @Test
    void runtimeConsoleShouldExposeAgentProductMode() throws IOException {
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));
        String adminView = Files.readString(projectRoot.resolve("frontend/src/views/AdminView.vue"));

        assertThat(types)
                .contains("export type AgentProductMode")
                .contains("productMode?: AgentProductMode | string")
                .contains("product_mode?: AgentProductMode | string");

        assertThat(agentView)
                .contains("productModeLabel")
                .contains("currentProductModeLabel")
                .contains("runProductMode");

        assertThat(adminView)
                .contains("<th>Mode</th>")
                .contains("productModeLabel")
                .contains("runProductMode");
    }

    @Test
    void agentTimelineShouldConsumeStructuredStepFields() throws IOException {
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));

        assertThat(types)
                .contains("stepSchema?: string")
                .contains("category?: string")
                .contains("action?: string")
                .contains("target?: string")
                .contains("source?: string")
                .contains("riskLevel?: string");

        assertThat(agentView)
                .contains("timelineStepDetail")
                .contains("event.target || event.stepName")
                .contains("step.source || step.riskLevel");
    }

    @Test
    void agentConsoleShouldExposeContextSummaryMetadata() throws IOException {
        String types = Files.readString(projectRoot.resolve("frontend/src/types.ts"));
        String agentView = Files.readString(projectRoot.resolve("frontend/src/views/AgentView.vue"));

        assertThat(types)
                .contains("export interface ContextSourceSummary")
                .contains("contextSummary?: ContextSourceSummary");

        assertThat(agentView)
                .contains("contextSummaryRows")
                .contains("streamMeta.value?.contextSummary")
                .contains("Memory Bank")
                .contains("Learning Rules")
                .contains("Not evaluated")
                .contains("Short-term Context")
                .contains("Semantic Memory")
                .contains("Observe Prompt")
                .contains("context-summary-grid");
    }
}
