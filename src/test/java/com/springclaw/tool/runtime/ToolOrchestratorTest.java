package com.springclaw.tool.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.workspace.WorkspaceReviewService;
import com.springclaw.service.workspace.WorkspaceTaskService;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.FileToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

class ToolOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldIncludeWorkspaceToolForProjectFileExistenceQuestion() {
        ToolFixture fixture = buildFixture();
        Object[] tools = fixture.orchestrator.selectTools(
                "api",
                "u1",
                "项目中是否存在 Spring AI 相关文件",
                "先确认配置和类是否存在"
        );

        Assertions.assertTrue(Arrays.asList(tools).contains(fixture.workspaceSearchToolPack));
    }

    @Test
    void shouldIncludeWorkspaceReviewToolForProjectReviewQuestion() {
        ToolFixture fixture = buildFixture();
        Object[] tools = fixture.orchestrator.selectTools(
                "api",
                "u1",
                "请审查这个项目源码，看看架构是否合理，有没有冗余垃圾代码",
                "先做本地 workspace review"
        );

        Assertions.assertTrue(Arrays.asList(tools).contains(fixture.workspaceReviewToolPack));
    }

    @Test
    void shouldIncludeFileToolForExplicitPathReadQuestion() {
        ToolFixture fixture = buildFixture();
        Object[] tools = fixture.orchestrator.selectTools(
                "api",
                "u1",
                "读取 src/main/resources/application.yml 文件",
                "直接读取指定路径"
        );

        Assertions.assertTrue(Arrays.asList(tools).contains(fixture.fileToolPack));
    }

    @Test
    void shouldIncludeLocalFilesystemToolForAuthorizedComputerFileQuestion() {
        ToolFixture fixture = buildFixture();
        Object[] tools = fixture.orchestrator.selectTools(
                "api",
                "u1",
                "帮我在本地电脑授权文件里找一下简历相关文件",
                "搜索授权目录"
        );

        Assertions.assertTrue(Arrays.asList(tools).contains(fixture.localFilesystemToolPack));
    }

    @Test
    void shouldIncludeSkillLibraryToolForSkillDiscoveryQuestion() {
        ToolFixture fixture = buildFixture();
        Object[] tools = fixture.orchestrator.selectTools(
                "api",
                "u1",
                "你现在有哪些 skills，可以打开 repo_inspector 看一下吗",
                "先按 Hermes 方式列出和查看 skill"
        );

        Assertions.assertTrue(Arrays.asList(tools).contains(fixture.skillLibraryToolPack));
    }

    @Test
    void shouldIncludeSkillLibraryToolForSkillUsageStatusQuestion() {
        ToolFixture fixture = buildFixture();
        Object[] tools = fixture.orchestrator.selectTools(
                "api",
                "u1",
                "查看 skill 使用统计和最近使用情况",
                "调用 skills_status"
        );

        Assertions.assertTrue(Arrays.asList(tools).contains(fixture.skillLibraryToolPack));
    }

    @Test
    void shouldSupportPluggableProviderWithoutChangingConstructorShape() {
        Object customTool = new Object();
        CapabilityRegistry registry = new CapabilityRegistry(List.of(entryFor(
                "custom",
                "custom",
                new String[]{"custom-trigger"},
                customTool
        )));
        ToolOrchestrator orchestrator = new ToolOrchestrator(new CustomSkillService(), registry);

        Object[] tools = orchestrator.selectTools("api", "u1", "please custom-trigger now", "");

        Assertions.assertTrue(Arrays.asList(tools).contains(customTool));
    }

    @Test
    void shouldFilterAgentToolsByDecisionCapabilities() {
        ToolFixture fixture = buildFixture();

        Object[] localTools = fixture.orchestrator.selectAgentTools(
                "api",
                "u1",
                new AgentDecision("local_files", "agent_tools", List.of("local-files", "file"), "read", false, "local")
        );

        Assertions.assertTrue(Arrays.asList(localTools).contains(fixture.localFilesystemToolPack));
        Assertions.assertTrue(Arrays.asList(localTools).contains(fixture.fileToolPack));
        Assertions.assertFalse(Arrays.asList(localTools).contains(fixture.workspaceReviewToolPack));
    }

    private ToolFixture buildFixture() {
        FileToolPack fileToolPack = new FileToolPack(tempDir.toString(), 12000);
        LocalFilesystemToolPack localFilesystemToolPack = new LocalFilesystemToolPack(
                new LocalFilesystemService(tempDir.toString(), ".ssh,.gnupg,.env", 12000, 8, 100, 20, 512)
        );
        SystemToolPack systemToolPack = new SystemToolPack(false, "pwd,ls", 5, 2000);
        WorkspaceTaskService workspaceTaskService = new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512);
        WorkspaceSearchToolPack workspaceSearchToolPack = new WorkspaceSearchToolPack(
                workspaceTaskService,
                tempDir.toString(),
                8,
                4000,
                30,
                5000,
                512
        );
        WorkspaceReviewToolPack workspaceReviewToolPack = new WorkspaceReviewToolPack(
                new WorkspaceReviewService(tempDir.toString(), 8, 300, 20, 512)
        );
        WebSearchToolPack webSearchToolPack = new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000);
        WeatherToolPack weatherToolPack = new WeatherToolPack(
                false,
                "https://example.com/{city}",
                3,
                "https://www.weather.com.cn/data/sk/{cityCode}.html",
                webSearchToolPack
        );
        ExchangeRateToolPack exchangeRateToolPack = new ExchangeRateToolPack(false, "https://example.com/{base}", 3);
        NewsToolPack newsToolPack = new NewsToolPack(false, "https://example.com/{query}", 5, 3);
        ScriptSkillCatalogService scriptSkillCatalogService = new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        SkillLibraryToolPack skillLibraryToolPack = new SkillLibraryToolPack(false, new com.springclaw.service.skill.bundle.SkillCatalogService(false, tempDir.toString()));
        SkillService skillService = new StubSkillService();

        CapabilityRegistry registry = new CapabilityRegistry(List.of(
                entryFor(fileToolPack),
                entryFor(localFilesystemToolPack),
                entryFor(systemToolPack),
                entryFor(workspaceSearchToolPack),
                entryFor(workspaceReviewToolPack),
                entryFor(webSearchToolPack),
                entryFor(weatherToolPack),
                entryFor(exchangeRateToolPack),
                entryFor(newsToolPack),
                entryFor(scriptSkillToolPack),
                entryFor(skillLibraryToolPack)
        ));
        ToolOrchestrator orchestrator = new ToolOrchestrator(skillService, registry);

        return new ToolFixture(orchestrator, fileToolPack, localFilesystemToolPack, workspaceSearchToolPack, workspaceReviewToolPack, skillLibraryToolPack);
    }

    private CapabilityRegistry.CapabilityEntry entryFor(Object toolPack) {
        ToolPackDescriptor descriptor = toolPack.getClass().getAnnotation(ToolPackDescriptor.class);
        return new CapabilityRegistry.CapabilityEntry(descriptor, toolPack, descriptor.id());
    }

    private CapabilityRegistry.CapabilityEntry entryFor(String id, String toolset, String[] triggerKeywords, Object toolPack) {
        ToolPackDescriptor descriptor = new ToolPackDescriptor() {
            @Override public String id() { return id; }
            @Override public String toolset() { return toolset; }
            @Override public String[] triggerKeywords() { return triggerKeywords; }
            @Override public boolean fallbackCandidate() { return true; }
            @Override public String riskLevel() { return "read"; }
            @Override public String preferredMode() { return "simplified"; }
            @Override public String description() { return id; }
            @Override public boolean includeForAgentMode() { return true; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return ToolPackDescriptor.class; }
        };
        return new CapabilityRegistry.CapabilityEntry(descriptor, toolPack, id);
    }

    private record ToolFixture(ToolOrchestrator orchestrator,
                               FileToolPack fileToolPack,
	                               LocalFilesystemToolPack localFilesystemToolPack,
	                               WorkspaceSearchToolPack workspaceSearchToolPack,
	                               WorkspaceReviewToolPack workspaceReviewToolPack,
                                   SkillLibraryToolPack skillLibraryToolPack) {
    }

    private static class StubSkillService implements SkillService {

        @Override
        public Set<String> resolveAllowedToolPacks(String channel, String userId) {
            return Set.of("system", "file", "workspace", "web", "weather", "exchange", "news", "script");
        }

        @Override
        public String describeAvailableSkills(String channel, String userId) {
            return "";
        }

        @Override
        public String describeCoreSkills(String channel, String userId) {
            return "";
        }
    }

    private static class CustomSkillService implements SkillService {

        @Override
        public Set<String> resolveAllowedToolPacks(String channel, String userId) {
            return Set.of("custom");
        }

        @Override
        public String describeAvailableSkills(String channel, String userId) {
            return "";
        }

        @Override
        public String describeCoreSkills(String channel, String userId) {
            return "";
        }
    }
}
