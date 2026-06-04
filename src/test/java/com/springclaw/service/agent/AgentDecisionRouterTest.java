package com.springclaw.service.agent;

import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDecisionRouterTest {

    @Test
    void shouldRouteGeneralQuestionToBasicModel() {
        AgentDecision decision = router().routeByRules(request("你好"));

        assertThat(decision.intent()).isEqualTo("general");
        assertThat(decision.executionPath()).isEqualTo("basic_model");
        assertThat(decision.selectedCapabilities()).isEmpty();
        assertThat(decision.requiresConfirmation()).isFalse();
    }

    @Test
    void shouldRouteWorkspaceQuestionToWorkspaceTools() {
        AgentDecision decision = router().routeByRules(request("请分析当前项目结构和源码模块"));

        assertThat(decision.intent()).isEqualTo("workspace_analysis");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).contains("workspace-search", "workspace-review", "file", "skill-library");
    }

    @Test
    void shouldRouteProjectAgentFlowQuestionToWorkspaceTools() {
        AgentDecision decision = router().routeByRules(request("帮我看看这个项目的Agent是怎么执行的"));

        assertThat(decision.intent()).isEqualTo("workspace_analysis");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).contains("workspace-search", "workspace-review", "file", "skill-library");
    }

    @Test
    void shouldRouteLocalFileQuestionToLocalFileTools() {
        AgentDecision decision = router().routeByRules(request("帮我读取桌面上的论文文件"));

        assertThat(decision.intent()).isEqualTo("local_files");
        assertThat(decision.selectedCapabilities()).contains("local-files", "file");
        assertThat(decision.riskLevel()).isEqualTo("read");
    }

    @Test
    void shouldRouteDesktopListingQuestionToLocalFileTools() {
        AgentDecision decision = router().routeByRules(request("看看桌面上有什么文件"));

        assertThat(decision.intent()).isEqualTo("local_files");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).contains("local-files", "file");
    }

    @Test
    void shouldTreatLocalFilesSkillMatchAsLocalFileIntentInsteadOfGenericSkillTask() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.matchBestAgentVisibleDefinition(any(), anySet())).thenReturn(Optional.of(localFilesSkill()));
        AgentDecision decision = new AgentDecisionRouter(skillRegistryService, new ToolRiskPolicyService())
                .routeByRules(request("总结一下电脑里的简历文件"));

        assertThat(decision.intent()).isEqualTo("local_files");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).contains("local-files", "file");
    }

    @Test
    void shouldRouteScheduledTaskToConfirmationDraft() {
        AgentDecision decision = router().routeByRules(request("每天 9 点帮我抓取这个网页"));

        assertThat(decision.intent()).isEqualTo("scheduled_task");
        assertThat(decision.executionPath()).isEqualTo("task_draft");
        assertThat(decision.riskLevel()).isEqualTo("side_effect");
        assertThat(decision.requiresConfirmation()).isTrue();
    }

    @Test
    void shouldGuardDangerousCommands() {
        AgentDecision decision = router().routeByRules(request("执行命令 rm -rf /"));

        assertThat(decision.riskLevel()).isEqualTo("dangerous");
        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(decision.executionPath()).isEqualTo("ask_clarification");
    }

    @Test
    void shouldRouteRuntimeHealthCheckToModelControlTools() {
        AgentDecision decision = router().routeByRules(request("检查后端启动、数据库、Redis、管理接口和模型配置是否正常。"));

        assertThat(decision.intent()).isEqualTo("model_control");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).contains("system", "skill-library");
    }

    private AgentDecisionRouter router() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.matchBestAgentVisibleDefinition(any(), anySet())).thenReturn(Optional.empty());
        return new AgentDecisionRouter(skillRegistryService, new ToolRiskPolicyService());
    }

    private AgentDecisionRequest request(String question) {
        return new AgentDecisionRequest("s1", "api", "u1", "USER", "req", question, "agent", Set.of("file", "workspace", "web", "script"));
    }

    private SkillDefinition localFilesSkill() {
        return new SkillDefinition(
                "local-files",
                "Local Files",
                "浏览授权本地文件",
                "builtin",
                "",
                "",
                List.of("桌面", "简历"),
                List.of(),
                List.of("file"),
                "agent",
                "",
                "builtin",
                "local-files",
                true,
                10,
                true
        );
    }
}
