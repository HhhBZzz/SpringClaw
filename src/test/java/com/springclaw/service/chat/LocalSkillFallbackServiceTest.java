package com.springclaw.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.config.ai.SpringClawAiProperties;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.ai.AiProviderStateService;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.service.workspace.WorkspaceReviewService;
import com.springclaw.service.workspace.WorkspaceTaskService;
import io.micrometer.observation.ObservationRegistry;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSkillFallbackServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAnswerAuthorizedLocalFileBoundaryDeterministically() throws Exception {
        Path documents = tempDir.resolve("Documents");
        Files.createDirectories(documents);
        Files.writeString(documents.resolve("resume.txt"), "resume");

        ScriptSkillCatalogService scriptSkillCatalogService = new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillExecutorService scriptSkillExecutorService = new ScriptSkillExecutorService(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        WorkspaceSearchToolPack workspaceSearchToolPack = new WorkspaceSearchToolPack(
                new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                tempDir.toString(),
                8,
                4000,
                30,
                5000,
                512
        );

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                workspaceSearchToolPack,
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                scriptSkillToolPack,
                new BuiltinSkillExecutionService(
                        registryService(),
                        workspaceSearchToolPack,
                        new WorkspaceReviewService(tempDir.toString(), 8, 300, 20, 512),
                        scriptSkillExecutorService,
                        scriptSkillCatalogService
                ),
                scriptSkillExecutorService,
                scriptSkillCatalogService,
                null,
                new LocalFilesystemService(documents.toString(), ".ssh,.env", 12000, 8, 100, 20, 512)
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleStructured("帮我列出当前授权本地目录，并说明是不是只能读取当前项目")
                .orElseThrow();

        Assertions.assertEquals("LOCAL_FILES_BOUNDARY", result.route());
        Assertions.assertTrue(result.fallbackAnswer().contains("不是只能读取当前项目目录"));
        Assertions.assertTrue(result.fallbackAnswer().contains("root1"));
        Assertions.assertTrue(result.fallbackAnswer().contains("Documents"));
    }

    @Test
    void shouldListDesktopFilesDeterministicallyForAuthorizedDesktopRequest() throws Exception {
        Path desktop = tempDir.resolve("Desktop");
        Files.createDirectories(desktop.resolve("大夏资料"));
        Files.createDirectories(desktop.resolve(".idea"));
        Files.createDirectories(desktop.resolve("01_%E4%BB%A3%E7%A0%81%E9%A1%B9%E7%9B%AE"));
        Files.writeString(desktop.resolve("时间序列试卷_完整试卷带答案清晰版.docx"), "binary-placeholder");
        Files.writeString(desktop.resolve("计算题补全清晰版.docx"), "binary-placeholder");
        Files.writeString(desktop.resolve(".DS_Store"), "noise");
        Files.writeString(desktop.resolve("~$计算题补全清晰版.docx"), "noise");

        ScriptSkillCatalogService scriptSkillCatalogService = new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillExecutorService scriptSkillExecutorService = new ScriptSkillExecutorService(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        WorkspaceSearchToolPack workspaceSearchToolPack = new WorkspaceSearchToolPack(
                new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                tempDir.toString(),
                8,
                4000,
                30,
                5000,
                512
        );

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                workspaceSearchToolPack,
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                scriptSkillToolPack,
                new BuiltinSkillExecutionService(
                        registryService(),
                        workspaceSearchToolPack,
                        new WorkspaceReviewService(tempDir.toString(), 8, 300, 20, 512),
                        scriptSkillExecutorService,
                        scriptSkillCatalogService
                ),
                scriptSkillExecutorService,
                scriptSkillCatalogService,
                null,
                new LocalFilesystemService(tempDir.toString(), ".ssh,.env", 12000, 8, 100, 20, 512)
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandlePriorityStructured("授权桌面全部")
                .orElseThrow();

        Assertions.assertEquals("LOCAL_FILES_DESKTOP_LIST", result.route());
        Assertions.assertTrue(result.fallbackAnswer().contains("桌面在当前授权范围内"));
        Assertions.assertTrue(result.fallbackAnswer().contains("时间序列试卷_完整试卷带答案清晰版.docx"));
        Assertions.assertTrue(result.fallbackAnswer().contains("计算题补全清晰版.docx"));
        Assertions.assertTrue(result.fallbackAnswer().contains("大夏资料"));
        Assertions.assertTrue(result.fallbackAnswer().contains("01_代码项目"));
        Assertions.assertFalse(result.fallbackAnswer().contains(".DS_Store"));
        Assertions.assertFalse(result.fallbackAnswer().contains(".idea"));
        Assertions.assertFalse(result.fallbackAnswer().contains("~$计算题"));
        Assertions.assertFalse(result.fallbackAnswer().contains("大夏..."));
        Assertions.assertFalse(result.fallbackAnswer().contains("EOF reached while reading"));
    }

    @Test
    void shouldRouteProjectFileQuestionToWorkspaceAnalysis() throws Exception {
        writeBuiltinSkill("code-analysis", "代码分析", "分析项目结构",
                "workspace,file,script", "opar",
                "分析代码,定位代码", "用代码分析分析 ChatServiceImpl");

        Path configFile = tempDir.resolve("src/main/java/com/springclaw/config/ai/SpringAiConfig.java");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                package com.springclaw.config.ai;

                public class SpringAiConfig {
                }
                """);

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

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                scriptSkillCatalogService,
                registryService(),
                mockAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleStructured("项目中是否存在 Spring AI 相关文件")
                .orElseThrow();

        Assertions.assertEquals("BUILTIN_SKILL:CODE_ANALYSIS", result.route());
        Assertions.assertTrue(result.executionDetails().contains("skill=code-analysis"));
        Assertions.assertFalse(result.fallbackAnswer().contains("WORKSPACE_TASK"));
        Assertions.assertTrue(result.fallbackAnswer().contains("SpringAiConfig.java"));
    }

    @Test
    void shouldRouteProjectReviewQuestionToWorkspaceReviewSkill() throws Exception {
        writeBuiltinSkill("workspace-review", "本地项目审查", "审查项目源码、架构和冗余代码",
                "workspace,file,script", "opar",
                "审查项目,项目审查,源码审查,架构审查,冗余代码,垃圾代码", "审查这个项目架构");

        Path serviceFile = tempDir.resolve("src/main/java/com/demo/service/UserService.java");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, """
                package com.demo.service;

                public class UserService {
                    // TODO split responsibilities
                    public void save() {
                    }
                }
                """);
        Files.writeString(tempDir.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");

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
        ScriptSkillExecutorService scriptSkillExecutorService = new ScriptSkillExecutorService(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());
        BuiltinSkillExecutionService builtinSkillExecutionService = new BuiltinSkillExecutionService(
                registryService(),
                workspaceSearchToolPack,
                new WorkspaceReviewService(tempDir.toString(), 8, 300, 20, 512),
                scriptSkillExecutorService,
                scriptSkillCatalogService
        );

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                builtinSkillExecutionService,
                scriptSkillExecutorService,
                scriptSkillCatalogService,
                mockAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleStructured("请审查这个项目源码，看看架构是否合理")
                .orElseThrow();

        Assertions.assertEquals("BUILTIN_SKILL:WORKSPACE_REVIEW", result.route());
        Assertions.assertTrue(result.executionDetails().contains("skill=workspace-review"));
        Assertions.assertTrue(result.fallbackAnswer().contains("LOCAL_WORKSPACE_REVIEW"));
        Assertions.assertTrue(result.fallbackAnswer().contains("UserService.java"));
    }

    @Test
    void shouldExtractRealCityForWeatherQuestion() {
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
        WebSearchToolPack webSearchToolPack = new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000);
        WeatherToolPack weatherToolPack = new StubWeatherToolPack();
        ExchangeRateToolPack exchangeRateToolPack = new ExchangeRateToolPack(false, "https://example.com/{base}", 3);
        NewsToolPack newsToolPack = new NewsToolPack(false, "https://example.com/{query}", 5, 3);
        ScriptSkillCatalogService scriptSkillCatalogService = new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                scriptSkillCatalogService,
                registryService(),
                mockAiProviderService()
        );

        String answer = service.tryHandle("查哈尔滨天气").orElse("");

        Assertions.assertTrue(answer.contains("哈尔滨"));
        Assertions.assertFalse(answer.contains("北京"));
    }

    @Test
    void shouldSwitchModelProviderByChatCommand() {
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
        WebSearchToolPack webSearchToolPack = new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000);
        WeatherToolPack weatherToolPack = new StubWeatherToolPack();
        ExchangeRateToolPack exchangeRateToolPack = new ExchangeRateToolPack(false, "https://example.com/{base}", 3);
        NewsToolPack newsToolPack = new NewsToolPack(false, "https://example.com/{query}", 5, 3);
        ScriptSkillCatalogService scriptSkillCatalogService = new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                scriptSkillCatalogService,
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult switchResult = service.tryHandleStructured("切换到 qwen").orElseThrow();
        String currentAnswer = service.tryHandle("当前模型是什么").orElse("");

        Assertions.assertEquals("MODEL_PROVIDER_SWITCH", switchResult.route());
        Assertions.assertTrue(switchResult.executionDetails().contains("provider: qwen"));
        Assertions.assertTrue(switchResult.fallbackAnswer().contains("已切换到 qwen"));
        Assertions.assertTrue(currentAnswer.contains("我当前使用的是 qwen 的 qwen3.5-plus"));
    }

    @Test
    void shouldSwitchCodingPlanSpecificModelByChatCommand() {
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
        WebSearchToolPack webSearchToolPack = new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000);
        WeatherToolPack weatherToolPack = new StubWeatherToolPack();
        ExchangeRateToolPack exchangeRateToolPack = new ExchangeRateToolPack(false, "https://example.com/{base}", 3);
        NewsToolPack newsToolPack = new NewsToolPack(false, "https://example.com/{query}", 5, 3);
        ScriptSkillCatalogService scriptSkillCatalogService = new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(false, scriptSkillCatalogService, "python3", 3, 1000, new ObjectMapper());

        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                scriptSkillCatalogService,
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult switchResult = service.tryHandleStructured("切换到千问 coder plus").orElseThrow();
        String currentAnswer = service.tryHandle("当前模型是什么").orElse("");
        String detailAnswer = service.tryHandle("列出所有模型").orElse("");

        Assertions.assertEquals("MODEL_PROVIDER_SWITCH", switchResult.route());
        Assertions.assertTrue(switchResult.executionDetails().contains("provider: coding-plan"));
        Assertions.assertTrue(switchResult.executionDetails().contains("model: qwen3-coder-plus"));
        Assertions.assertTrue(switchResult.fallbackAnswer().contains("已切换到 coding-plan"));
        Assertions.assertTrue(currentAnswer.contains("我当前使用的是 coding-plan 的 qwen3-coder-plus"));
        Assertions.assertTrue(detailAnswer.contains("当前模型状态"));
        Assertions.assertTrue(detailAnswer.contains("当前活动 provider: coding-plan"));
        Assertions.assertTrue(detailAnswer.contains("当前模型: qwen3-coder-plus"));
        Assertions.assertTrue(detailAnswer.contains("qwen3-coder-next"));
    }

    @Test
    void shouldSwitchToQwen35PlusWhenQuestionExplicitlyMentionsQwen35() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult switchResult = service.tryHandleStructured("切换为千问3.5").orElseThrow();

        Assertions.assertEquals("MODEL_PROVIDER_SWITCH", switchResult.route());
        Assertions.assertTrue(switchResult.executionDetails().contains("provider: coding-plan"));
        Assertions.assertTrue(switchResult.executionDetails().contains("model: qwen3.5-plus"));
        Assertions.assertTrue(switchResult.fallbackAnswer().contains("qwen3.5-plus"));
    }

    @Test
    void shouldSwitchToDeepSeekReasonerByChatCommand() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult switchResult = service.tryHandleStructured("切换到 DeepSeek Reasoner").orElseThrow();
        String currentAnswer = service.tryHandle("当前模型是什么").orElse("");
        String detailAnswer = service.tryHandle("列出所有模型").orElse("");

        Assertions.assertEquals("MODEL_PROVIDER_SWITCH", switchResult.route());
        Assertions.assertTrue(switchResult.executionDetails().contains("模型切换失败"));
        Assertions.assertTrue(switchResult.executionDetails().contains("暂不支持"));
        Assertions.assertFalse(currentAnswer.contains("deepseek-reasoner"));
        Assertions.assertTrue(detailAnswer.contains("deepseek"));
        Assertions.assertTrue(detailAnswer.contains("deepseek-v4-pro"));
        Assertions.assertFalse(detailAnswer.contains("deepseek-reasoner"));
    }

    @Test
    void shouldAnswerSwitchableModelsForNaturalAvailabilityQuestion() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleControlPlane("可以更换为什么模型").orElseThrow();

        Assertions.assertEquals("MODEL_AVAILABILITY_QUERY", result.route());
        Assertions.assertTrue(result.fallbackAnswer().contains("当前可切换的模型有"));
        Assertions.assertTrue(result.fallbackAnswer().contains("qwen3.5-plus"));
        Assertions.assertTrue(result.fallbackAnswer().contains("qwen3-coder-plus"));
    }

    @Test
    void shouldConfirmQwen35PlusAvailability() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleControlPlane("没有千问3.5plus么").orElseThrow();

        Assertions.assertEquals("MODEL_AVAILABILITY_QUERY", result.route());
        Assertions.assertTrue(result.fallbackAnswer().contains("有"));
        Assertions.assertTrue(result.fallbackAnswer().contains("qwen3.5-plus"));
    }

    @Test
    void shouldAnswerCurrentTimeFromControlPlane() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleControlPlane("现在是什么时候").orElseThrow();

        Assertions.assertEquals("SYSTEM_TIME", result.route());
        Assertions.assertTrue(result.fallbackAnswer().contains(":"));
    }

    @Test
    void shouldAnswerCurrentModelForMuQianPhraseFromControlPlane() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result = service.tryHandleControlPlane("目前是什么模型").orElseThrow();

        Assertions.assertEquals("MODEL_PROVIDER_QUERY", result.route());
        Assertions.assertTrue(result.fallbackAnswer().contains("我当前使用的是"));
    }

    @Test
    void dateQuestionShouldNotBeInterceptedByControlPlaneButShouldRemainAvailableForDegradedFallback() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        Assertions.assertTrue(service.tryHandleControlPlane("今天是什么日子").isEmpty());

        LocalSkillFallbackService.LocalSkillResult answer = service.tryHandleStructured("今天是什么日子").orElseThrow();

        Assertions.assertEquals("SYSTEM_DATE", answer.route());
        Assertions.assertTrue(answer.fallbackAnswer().contains("今天是"));
        Assertions.assertTrue(answer.fallbackAnswer().contains("星期"));
    }

    @Test
    void controlPlaneRoutingShouldNotInterceptWeatherQuestion() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        Assertions.assertTrue(service.tryHandleControlPlane("北京天气怎么样").isEmpty());
        Assertions.assertTrue(service.tryHandleStructured("北京天气怎么样").isPresent());
    }

    @Test
    void shouldNotInterceptGenericCapabilityQuestion() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        Assertions.assertTrue(service.tryHandle("你能做什么").isEmpty());
    }

    @Test
    void shouldNotInterceptGenericSkillsQuestion() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        Assertions.assertTrue(service.tryHandle("你有哪些技能").isEmpty());
    }

    @Test
    void shouldNotInterceptJvmKnowledgeQuestion() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        Assertions.assertTrue(service.tryHandle("JVM 是什么").isEmpty());
    }

    @Test
    void shouldNotTreatGenericPythonQuestionAsScriptSkillIntent() {
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                new ScriptSkillToolPack(false,
                        new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                        "python3", 3, 1000, new ObjectMapper()),
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                registryService(),
                newAiProviderService()
        );

        Assertions.assertTrue(service.tryHandle("Python 和 Java 的区别是什么").isEmpty());
    }

    @Test
    void shouldAutoRouteHighConfidenceScriptSkillWithoutHardcodedJavaBranch() throws Exception {
        Path statusDir = tempDir.resolve("system_status");
        Files.createDirectories(statusDir.resolve("scripts"));
        Files.writeString(statusDir.resolve("scripts/run.py"), """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print("skill=system_status")
                print("goal=" + payload.get("goal", ""))
                """);
        Files.writeString(statusDir.resolve("SKILL.md"), """
                ---
                name: 系统状态
                description: 查看系统状态
                skillId: system_status
                type: python
                entrypoint: scripts/run.py
                category: runtime
                tier: core
                inputHint: goal
                priority: 30
                enabled: true
                agentVisible: true
                triggerKeywords:
                  - 系统状态
                ---
                # 系统状态
                """);

        ScriptSkillCatalogService scriptSkillCatalogService =
                new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
        ScriptSkillExecutorService scriptSkillExecutorService =
                new ScriptSkillExecutorService(true, scriptSkillCatalogService, "python3", 5, 2000, new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack =
                new ScriptSkillToolPack(true, scriptSkillCatalogService, "python3", 5, 2000, new ObjectMapper());
        WorkspaceSearchToolPack workspaceSearchToolPack = new WorkspaceSearchToolPack(
                new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                tempDir.toString(),
                8,
                4000,
                30,
                5000,
                512
        );
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                workspaceSearchToolPack,
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                scriptSkillToolPack,
                new BuiltinSkillExecutionService(
                        registryService(),
                        workspaceSearchToolPack,
                        new WorkspaceReviewService(tempDir.toString(), 8, 300, 20, 512),
                        scriptSkillExecutorService,
                        scriptSkillCatalogService
                ),
                scriptSkillExecutorService,
                scriptSkillCatalogService,
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result =
                service.tryHandleStructured("查看系统状态").orElseThrow();

        Assertions.assertEquals("SCRIPT_SKILL_AUTO", result.route());
        Assertions.assertTrue(result.executionDetails().contains("skill=system_status"));
        Assertions.assertTrue(result.fallbackAnswer().contains("查看系统状态"));
    }

    @Test
    void shouldUsePythonWebSkillForExplicitWebFetchQuestion() throws Exception {
        writeBuiltinSkill("web-crawl", "网页抓取", "抓取网页正文",
                "script", "simplified",
                "抓取网页,读取网页,网页正文", "读取这个网页 https://example.com");

        // 创建 web_crawler python script skill 供 ScriptSkillCatalogService 扫描
        Path webCrawlerDir = tempDir.resolve("web_crawler");
        Files.createDirectories(webCrawlerDir.resolve("scripts"));
        Files.writeString(webCrawlerDir.resolve("scripts/run.py"), """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print("title=demo")
                print("url=" + payload.get("goal", ""))
                """);
        Files.writeString(webCrawlerDir.resolve("SKILL.md"), """
                ---
                name: 网页抓取
                description: 抓取网页正文
                skillId: web_crawler
                type: python
                entrypoint: scripts/run.py
                category: web
                tier: core
                inputHint: goal
                priority: 50
                enabled: true
                agentVisible: true
                triggerKeywords:
                  - 抓取网页
                  - 读取网页
                ---
                # 网页抓取
                """);

        ScriptSkillCatalogService scriptSkillCatalogService =
                new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
        ScriptSkillToolPack scriptSkillToolPack = new ScriptSkillToolPack(
                true,
                scriptSkillCatalogService,
                "python3",
                5,
                2000,
                new ObjectMapper()
        );
        LocalSkillFallbackService service = new LocalSkillFallbackService(
                true,
                new SystemToolPack(false, "pwd,ls", 5, 2000),
                new WorkspaceSearchToolPack(
                        new WorkspaceTaskService(tempDir.toString(), 8, 4, 6, 1200, 512),
                        tempDir.toString(),
                        8,
                        4000,
                        30,
                        5000,
                        512
                ),
                new WebSearchToolPack(false, "https://example.com?q={query}", true, 3, 2000),
                new StubWeatherToolPack(),
                new ExchangeRateToolPack(false, "https://example.com/{base}", 3),
                new NewsToolPack(false, "https://example.com/{query}", 5, 3),
                scriptSkillToolPack,
                scriptSkillCatalogService,
                registryService(),
                newAiProviderService()
        );

        LocalSkillFallbackService.LocalSkillResult result =
                service.tryHandlePriorityStructured("读取这个网页 https://example.com/docs").orElseThrow();

        Assertions.assertEquals("BUILTIN_SKILL:WEB_CRAWL", result.route());
        Assertions.assertTrue(result.executionDetails().contains("skill=web-crawl"));
        Assertions.assertTrue(result.fallbackAnswer().contains("title=demo"));
        Assertions.assertTrue(result.fallbackAnswer().contains("https://example.com/docs"));
    }

    private SkillRegistryService registryService() {
        return new SkillRegistryService(new SkillCatalogService(true, tempDir.toString()));
    }

    private void writeBuiltinSkill(String skillId, String name, String description,
                                    String toolPacksCsv, String preferredMode,
                                    String triggerKeywordsCsv, String triggerExample) throws Exception {
        Path dir = tempDir.resolve(skillId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                skillId: %s
                type: builtin
                category: builtin
                tier: core
                inputHint: test
                priority: 10
                enabled: true
                agentVisible: true
                toolPacks:
                %s
                preferredMode: %s
                triggerKeywords:
                %s
                triggerExamples:
                  - %s
                ---
                # %s
                内建技能说明。
                """.formatted(
                name, description, skillId,
                indentList(toolPacksCsv), preferredMode,
                indentList(triggerKeywordsCsv), triggerExample, name));
    }

    private String indentList(String csv) {
        StringBuilder builder = new StringBuilder();
        for (String token : csv.split(",")) {
            String value = token.trim();
            if (!value.isEmpty()) {
                builder.append("        - ").append(value).append("\n");
            }
        }
        return builder.toString();
    }

    private AiProviderService mockAiProviderService() {
        return newAiProviderService();
    }

    private AiProviderService newAiProviderService() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getPrimary().setApiKey("primary-test-key");
        properties.getProviders().getPrimary().setBaseUrl("https://api.example.com");
        properties.getProviders().getPrimary().setModel("claude-opus-4-6");
        properties.getProviders().getQwen().setApiKey("qwen-test-key");
        properties.getProviders().getQwen().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getProviders().getQwen().setModel("qwen3.5-plus");
        properties.getProviders().getQwen().setModels(List.of("qwen3.5-plus"));
        properties.getProviders().getCodingPlan().setApiKey("coding-plan-test-key");
        properties.getProviders().getCodingPlan().setBaseUrl("https://coding.dashscope.aliyuncs.com/v1");
        properties.getProviders().getCodingPlan().setModel("qwen3.5-plus");
        properties.getProviders().getCodingPlan().setModels(List.of("qwen3.5-plus", "qwen3-coder-plus", "qwen3-coder-next"));
        properties.getProviders().getDeepSeek().setApiKey("deepseek-test-key");
        properties.getProviders().getDeepSeek().setBaseUrl("https://api.deepseek.com");
        properties.getProviders().getDeepSeek().setModel("deepseek-v4-pro");
        properties.getProviders().getDeepSeek().setModels(List.of("deepseek-v4-pro"));
        properties.setActiveProvider("primary");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClientBuilder", RestClient.builder());
        beanFactory.addBean("webClientBuilder", WebClient.builder());
        ObjectProvider<RestClient.Builder> restProvider = beanFactory.getBeanProvider(RestClient.Builder.class);
        ObjectProvider<WebClient.Builder> webProvider = beanFactory.getBeanProvider(WebClient.Builder.class);
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
        AiProviderStateService stateService = mock(AiProviderStateService.class);
        when(stateService.resolvePreferredProvider("primary")).thenReturn("primary");
        when(stateService.resolvePreferredModel(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.switchMode()).thenReturn("runtime-memory");

        return new AiProviderService(
                properties,
                stateService,
                mock(ToolCallingManager.class),
                retryTemplate,
                ObservationRegistry.NOOP,
                restProvider,
                webProvider
        );
    }

    private static final class StubWeatherToolPack extends WeatherToolPack {

        private StubWeatherToolPack() {
            super(true, "https://example.com/{city}", 3, "https://example.com/{cityCode}");
        }

        @Override
        public String queryWeather(String city) {
            return "城市: " + city;
        }
    }
}
