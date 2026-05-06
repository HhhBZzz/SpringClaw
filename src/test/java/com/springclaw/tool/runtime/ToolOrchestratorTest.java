package com.springclaw.tool.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
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

        ToolOrchestrator orchestrator = new ToolOrchestrator(
                fileToolPack,
                localFilesystemToolPack,
                systemToolPack,
                workspaceSearchToolPack,
                workspaceReviewToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                skillLibraryToolPack,
                skillService,
                "文件,目录,path,read,write,list,保存,读取",
                "本地文件,电脑文件,授权文件,授权目录,本机文件,桌面,下载,文档,Desktop,Downloads,Documents,简历,论文",
                "找文件,搜代码,在哪个文件,关键词检索,不知道路径,search file,find file,grep",
                "联网,搜索,查一下,网页,新闻,官网,web search,google,bing",
                "天气,气温,温度,下雨,weather",
                "汇率,美元,人民币,欧元,exchange,usd,cny,eur",
                "新闻,热点,头条,资讯,news",
                "脚本,skill,执行技能,python,run skill"
        );

        return new ToolFixture(orchestrator, fileToolPack, localFilesystemToolPack, workspaceSearchToolPack, workspaceReviewToolPack, skillLibraryToolPack);
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
}
