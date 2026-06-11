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
}
