package com.springclaw.service.agent;

import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.tool.runtime.CapabilityRegistry;
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
    void shouldRouteHighConfidenceCodeAnalysisSkillToWorkspaceTools() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.matchHighConfidenceDefinition(any())).thenReturn(Optional.of(codeAnalysisSkill()));
        when(skillRegistryService.matchBestAgentVisibleDefinition(any(), anySet())).thenReturn(Optional.empty());

        AgentDecision decision = new AgentDecisionRouter(skillRegistryService, new ToolRiskPolicyService(), null)
                .routeByRules(request("分析 springclaw 项目架构"));

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
    void shouldRouteDesktopTxtWriteToConfirmedFileTool() {
        AgentDecision decision = router().routeByRules(request("请在桌面创建一个笑话.txt 文件，写几条笑话"));

        assertThat(decision.intent()).isEqualTo("local_files");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).containsExactly("local-files", "file");
        assertThat(decision.riskLevel()).isEqualTo("write");
        assertThat(decision.requiresConfirmation()).isTrue();
    }

    @Test
    void shouldRouteDesktopDocumentWriteToConfirmedFileTool() {
        AgentDecision decision = router().routeByRules(request("请在桌面创建一个笑话文档，写几条笑话"));

        assertThat(decision.intent()).isEqualTo("local_files");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.riskLevel()).isEqualTo("write");
        assertThat(decision.requiresConfirmation()).isTrue();
    }

    @Test
    void shouldRouteGenericLocalMarkdownWriteToConfirmedFileTool() {
        AgentDecision decision = router().routeByRules(request("新建一个本地 notes.md 文件，写入会议纪要"));

        assertThat(decision.intent()).isEqualTo("local_files");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).containsExactly("local-files", "file");
        assertThat(decision.riskLevel()).isEqualTo("write");
        assertThat(decision.requiresConfirmation()).isTrue();
    }

    @Test
    void shouldTreatLocalFilesSkillMatchAsLocalFileIntentInsteadOfGenericSkillTask() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.matchBestAgentVisibleDefinition(any(), anySet())).thenReturn(Optional.of(localFilesSkill()));
        AgentDecision decision = new AgentDecisionRouter(skillRegistryService, new ToolRiskPolicyService(), null)
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

    @Test
    void shouldRouteNaturalLanguageModelSwitchToModelControlWithoutConfirmation() {
        AgentDecision decision = router().routeByRules(request("切换DeepSeek模型"));

        assertThat(decision.intent()).isEqualTo("model_control");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).contains("system", "skill-library");
        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.riskLevel()).isEqualTo("read");
    }

    @Test
    void shouldRouteWeatherQuestionToWeatherPrimaryCapability() {
        AgentDecision decision = router().routeByRules(request("哈尔滨天气怎样"));

        assertThat(decision.intent()).isEqualTo("web_research");
        assertThat(decision.executionPath()).isEqualTo("agent_tools");
        assertThat(decision.selectedCapabilities()).containsExactly("weather");
    }

    @Test
    void shouldRouteWeatherSynonymsToWeatherCapability() {
        AgentDecisionRouter router = router();

        assertThat(router.routeByRules(request("北京温度多少")).selectedCapabilities()).containsExactly("weather");
        assertThat(router.routeByRules(request("明天上海下雨吗")).selectedCapabilities()).containsExactly("weather");
        assertThat(router.routeByRules(request("Beijing weather")).selectedCapabilities()).containsExactly("weather");
    }

    @Test
    void shouldSelectMatchedCapabilityIdFromRegistryInsteadOfHardcodedWebList() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.matchBestAgentVisibleDefinition(any(), anySet())).thenReturn(Optional.empty());
        CapabilityRegistry registry = new CapabilityRegistry(List.of(
                CapabilityRegistry.entryForTest("browser", "web",
                        new String[]{"浏览器", "打开网页"}, true, "read", "本地浏览器操作")
        ));

        AgentDecision decision = new AgentDecisionRouter(skillRegistryService, new ToolRiskPolicyService(), registry)
                .routeByRules(request("用浏览器打开网页看看"));

        assertThat(decision.intent()).isEqualTo("web_research");
        assertThat(decision.selectedCapabilities()).containsExactly("browser");
    }

    private AgentDecisionRouter router() {
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.matchBestAgentVisibleDefinition(any(), anySet())).thenReturn(Optional.empty());
        CapabilityRegistry registry = new CapabilityRegistry(List.of(
                CapabilityRegistry.entryForTest("system", "system",
                        new String[]{"检查后端", "数据库", "Redis", "运行状态", "模型配置", "provider", "当前模型"}, true, "read", "系统运行状态"),
                CapabilityRegistry.entryForTest("workspace-search", "workspace",
                        new String[]{"项目", "源码", "代码", "架构", "模块", "分析", "审查", "agent", "链路", "执行", "梳理"}, true, "read", "检索项目文件"),
                CapabilityRegistry.entryForTest("local-files", "file",
                        new String[]{"桌面", "论文", "本地文件", "简历", "docx", "pdf"}, true, "read", "读取授权本地文件"),
                CapabilityRegistry.entryForTest("web", "web",
                        new String[]{"搜索", "网页", "官网"}, true, "read", "联网搜索"),
                CapabilityRegistry.entryForTest("weather", "web",
                        new String[]{"天气", "气温", "温度", "下雨", "weather"}, true, "read", "查询天气"),
                CapabilityRegistry.entryForTest("script-skill", "script",
                        new String[]{"skill", "脚本", "运行", "执行", "python"}, true, "execution", "执行脚本技能")
        ));
        return new AgentDecisionRouter(skillRegistryService, new ToolRiskPolicyService(), registry);
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

    private SkillDefinition codeAnalysisSkill() {
        return new SkillDefinition(
                "code-analysis",
                "Code Analysis",
                "分析项目结构、类、文件和实现位置",
                "builtin",
                "",
                "",
                List.of("代码分析", "分析代码", "分析项目", "项目结构"),
                List.of(),
                List.of("workspace", "file", "script"),
                "opar",
                "",
                "builtin",
                "code-analysis",
                true,
                10,
                true,
                false,
                List.of("项目架构", "项目结构", "代码结构")
        );
    }
}
