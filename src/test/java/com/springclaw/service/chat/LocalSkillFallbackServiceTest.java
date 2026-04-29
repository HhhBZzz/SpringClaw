package com.springclaw.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.config.ai.SpringClawAiProperties;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.ai.AiProviderStateService;
import com.springclaw.service.skill.bundle.SkillPackageCatalogService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
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
        Assertions.assertTrue(result.fallbackAnswer().contains("SpringAiConfig.java"));
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
        Assertions.assertTrue(switchResult.executionDetails().contains("provider: deepseek"));
        Assertions.assertTrue(switchResult.executionDetails().contains("model: deepseek-reasoner"));
        Assertions.assertTrue(switchResult.fallbackAnswer().contains("已切换到 deepseek"));
        Assertions.assertTrue(currentAnswer.contains("我当前使用的是 deepseek 的 deepseek-reasoner"));
        Assertions.assertTrue(detailAnswer.contains("deepseek"));
        Assertions.assertTrue(detailAnswer.contains("deepseek-chat"));
        Assertions.assertTrue(detailAnswer.contains("deepseek-reasoner"));
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
        return new SkillRegistryService(new SkillPackageCatalogService(true, tempDir.toString()));
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
        properties.getProviders().getDeepSeek().setModel("deepseek-chat");
        properties.getProviders().getDeepSeek().setModels(List.of("deepseek-chat", "deepseek-reasoner"));
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
