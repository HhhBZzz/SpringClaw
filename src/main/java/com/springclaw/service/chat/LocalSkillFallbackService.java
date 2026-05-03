package com.springclaw.service.chat;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.service.skill.script.ScriptSkillDefinition;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * 本地技能兜底服务。
 *
 * 设计说明：
 * 1. 当模型不可用或回答偏离时，使用规则路由到现有 ToolPack，保证“可执行能力”。
 * 2. 仅处理高频强确定性意图（天气/汇率/新闻/检索/时间），避免误判干扰主链路。
 */
@Service
public class LocalSkillFallbackService {

    private final boolean enabled;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final WebSearchToolPack webSearchToolPack;
    private final WeatherToolPack weatherToolPack;
    private final ExchangeRateToolPack exchangeRateToolPack;
    private final NewsToolPack newsToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;
    private final BuiltinSkillExecutionService builtinSkillExecutionService;
    private final LocalSkillModelControlSupport modelControlSupport;
    private final LocalSkillQuerySupport querySupport;
    private final LocalFilesystemService localFilesystemService;

    LocalSkillFallbackService(boolean enabled,
                              SystemToolPack systemToolPack,
                              WorkspaceSearchToolPack workspaceSearchToolPack,
                              WebSearchToolPack webSearchToolPack,
                              WeatherToolPack weatherToolPack,
                              ExchangeRateToolPack exchangeRateToolPack,
                              NewsToolPack newsToolPack,
                              ScriptSkillToolPack scriptSkillToolPack,
                              ScriptSkillCatalogService scriptSkillCatalogService,
                              SkillRegistryService skillRegistryService,
                              AiProviderService aiProviderService) {
        this(enabled,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                createBuiltinSkillExecutionService(enabled, workspaceSearchToolPack, scriptSkillCatalogService, skillRegistryService),
                createScriptSkillExecutorService(enabled, scriptSkillCatalogService),
                scriptSkillCatalogService,
                aiProviderService,
                null);
    }

    public LocalSkillFallbackService(boolean enabled,
                                     SystemToolPack systemToolPack,
                                     WorkspaceSearchToolPack workspaceSearchToolPack,
                                     WebSearchToolPack webSearchToolPack,
                                     WeatherToolPack weatherToolPack,
                                     ExchangeRateToolPack exchangeRateToolPack,
                                     NewsToolPack newsToolPack,
                                     ScriptSkillToolPack scriptSkillToolPack,
                                     BuiltinSkillExecutionService builtinSkillExecutionService,
                                     ScriptSkillExecutorService scriptSkillExecutorService,
                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                     AiProviderService aiProviderService) {
        this(enabled,
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
                aiProviderService,
                null);
    }

    @Autowired
    public LocalSkillFallbackService(@Value("${springclaw.chat.local-fallback-enabled:true}") boolean enabled,
                                     SystemToolPack systemToolPack,
                                     WorkspaceSearchToolPack workspaceSearchToolPack,
                                     WebSearchToolPack webSearchToolPack,
                                     WeatherToolPack weatherToolPack,
                                     ExchangeRateToolPack exchangeRateToolPack,
                                     NewsToolPack newsToolPack,
                                     ScriptSkillToolPack scriptSkillToolPack,
                                     BuiltinSkillExecutionService builtinSkillExecutionService,
                                     ScriptSkillExecutorService scriptSkillExecutorService,
                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                     AiProviderService aiProviderService,
                                     LocalFilesystemService localFilesystemService) {
        this.enabled = enabled;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.newsToolPack = newsToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.builtinSkillExecutionService = builtinSkillExecutionService;
        this.modelControlSupport = new LocalSkillModelControlSupport(systemToolPack, aiProviderService);
        this.querySupport = new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService);
        this.localFilesystemService = localFilesystemService;
    }

    private static ScriptSkillExecutorService createScriptSkillExecutorService(boolean enabled,
                                                                               ScriptSkillCatalogService scriptSkillCatalogService) {
        return new ScriptSkillExecutorService(
                enabled,
                scriptSkillCatalogService,
                "python3",
                8,
                3000,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
    }

    private static BuiltinSkillExecutionService createBuiltinSkillExecutionService(boolean enabled,
                                                                                   WorkspaceSearchToolPack workspaceSearchToolPack,
                                                                                   ScriptSkillCatalogService scriptSkillCatalogService,
                                                                                   SkillRegistryService skillRegistryService) {
        return new BuiltinSkillExecutionService(
                skillRegistryService,
                workspaceSearchToolPack,
                createScriptSkillExecutorService(enabled, scriptSkillCatalogService),
                scriptSkillCatalogService
        );
    }

    public Optional<String> tryHandle(String question) {
        return tryHandleStructured(question).map(LocalSkillResult::fallbackAnswer);
    }

    public Optional<LocalSkillResult> tryHandleControlPlane(String question) {
        if (!enabled) {
            return Optional.empty();
        }
        return modelControlSupport.tryHandleControlPlane(question);
    }

    public Optional<LocalSkillResult> tryHandlePriorityStructured(String question) {
        if (!enabled || !StringUtils.hasText(question)) {
            return Optional.empty();
        }
        return builtinSkillExecutionService.tryExecuteHighConfidence(question.trim());
    }

    public Optional<LocalSkillResult> tryHandleStructured(String question) {
        if (!enabled || !StringUtils.hasText(question)) {
            return Optional.empty();
        }

        String q = question.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        Optional<LocalSkillResult> controlPlane = tryHandleControlPlane(q);
        if (controlPlane.isPresent()) {
            return controlPlane;
        }

        if (looksLikeLocalFileBoundaryQuestion(lower)) {
            return renderLocalFileBoundary();
        }

        Optional<LocalSkillResult> builtinSkill = builtinSkillExecutionService.tryExecute(q);
        if (builtinSkill.isPresent()) {
            return builtinSkill;
        }

        if (modelControlSupport.looksLikeDateQuestion(lower)) {
            String answer = modelControlSupport.renderTodayInfo();
            return localResult("SYSTEM_DATE", answer, answer, false);
        }

        if (containsAny(lower, "脚本技能列表", "有哪些脚本技能", "可用脚本技能", "script skill", "script skills")) {
            String answer = scriptSkillToolPack.listScriptSkills();
            return localResult("SCRIPT_SKILL_LIST", answer, answer, true);
        }

        if (containsAny(lower, "端口占用", "谁占用了", "启动失败", "服务没起来", "port", "端口被占用", "诊断运行")) {
            String answer = querySupport.runScriptSkillByCategory("runtime", q);
            if (StringUtils.hasText(answer)) {
                return localResult("RUNTIME_DIAGNOSIS", answer, answer, true);
            }
            String fallback = "未找到可用的运行诊断 skill，请检查 skills 目录与白名单配置。";
            return localResult("RUNTIME_DIAGNOSIS", fallback, fallback, false);
        }

        if (containsAny(lower, "天气", "气温", "下雨", "weather")) {
            String city = querySupport.extractCity(q);
            String answer = weatherToolPack.queryWeather(city);
            if (querySupport.looksLikeFailure(answer)) {
                String skillAnswer = querySupport.runScriptSkillByCategory("weather", q);
                if (StringUtils.hasText(skillAnswer)) {
                    return localResult("WEATHER_QUERY", skillAnswer, skillAnswer, true);
                }
            }
            return localResult("WEATHER_QUERY", answer, answer, true);
        }

        if (containsAny(lower, "汇率", "exchange", "美元", "人民币", "欧元", "日元")) {
            String[] currencies = querySupport.extractCurrencies(q);
            String answer = exchangeRateToolPack.queryExchangeRate(currencies[0], currencies[1]);
            return localResult("EXCHANGE_RATE", answer, answer, true);
        }

        if (containsAny(lower, "新闻", "热点", "资讯", "news")) {
            String keyword = querySupport.extractNewsKeyword(q);
            String answer = newsToolPack.searchNews(keyword);
            return localResult("NEWS_SEARCH", answer, answer, true);
        }

        if (querySupport.looksLikeExplicitWebFetchQuestion(q)) {
            String answer = querySupport.runScriptSkillByCategory("web", q);
            if (StringUtils.hasText(answer)) {
                return localResult("WEB_CRAWL", answer, answer, true);
            }
            String target = querySupport.extractFirstUrl(q);
            String fallback = StringUtils.hasText(target)
                    ? "未找到可用的网页抓取 Python skill，目标链接: " + target
                    : "未找到可用的网页抓取 Python skill。";
            return localResult("WEB_CRAWL", fallback, fallback, false);
        }

        if (querySupport.looksLikeWorkspaceQuestion(lower)) {
            String answer = workspaceSearchToolPack.analyzeWorkspaceTask(q);
            if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
                String skillAnswer = querySupport.runScriptSkillByCategory("workspace", q);
                if (StringUtils.hasText(skillAnswer)) {
                    return localResult("WORKSPACE_ANALYSIS", skillAnswer, querySupport.renderWorkspaceAnswer(skillAnswer), true);
                }
            }
            return localResult("WORKSPACE_ANALYSIS", answer, querySupport.renderWorkspaceAnswer(answer), true);
        }

        if (querySupport.looksLikeExplicitWebSearchQuestion(lower)) {
            String keyword = querySupport.extractWebKeyword(q);
            String answer = webSearchToolPack.webSearch(keyword);
            return localResult("WEB_SEARCH", answer, answer, true);
        }

        if (querySupport.looksLikeExplicitScriptSkillQuestion(lower)) {
            ScriptSkillDefinition skillDefinition = querySupport.resolveRequestedScriptSkill(q);
            if (skillDefinition == null) {
                String answer = scriptSkillToolPack.listScriptSkills();
                return localResult("SCRIPT_SKILL_LIST", answer, answer, true);
            }
            String answer = scriptSkillToolPack.runScriptSkillByGoal(skillDefinition.skillName(), q);
            return localResult("SCRIPT_SKILL_RUN", answer, answer, true);
        }

        return Optional.empty();
    }

    private boolean looksLikeLocalFileBoundaryQuestion(String lower) {
        return containsAny(lower,
                "本地文件边界", "本地文件权限", "授权本地目录", "授权目录", "授权根目录",
                "授权文件", "电脑文件", "本机文件", "能读取哪些本地", "读取边界",
                "只能读取当前项目", "只能看当前项目", "当前项目目录");
    }

    private Optional<LocalSkillResult> renderLocalFileBoundary() {
        if (localFilesystemService == null) {
            return Optional.empty();
        }
        String roots;
        try {
            roots = localFilesystemService.listAuthorizedRoots();
        } catch (Exception ex) {
            roots = "授权目录读取失败: " + ex.getMessage();
        }
        String answer = """
                结论：不是只能读取当前项目目录。

                当前有两类本地文件能力：
                1. 项目工作区：用于审查当前项目源码、配置、脚本和文档。
                2. Local Files：用于浏览、搜索和读取你明确授权的本机目录。

                当前授权根目录：
                %s

                安全边界：
                1. 只能读取授权根目录内的非敏感文本文件。
                2. 不读取 .ssh、.gnupg、Keychains、浏览器 Profile、.env 等敏感路径。
                3. 不越权访问授权目录之外的系统路径。
                """.formatted(roots);
        return localResult("LOCAL_FILES_BOUNDARY", answer, answer, false);
    }

    private Optional<LocalSkillResult> localResult(String route, String executionDetails, String fallbackAnswer, boolean detailed) {
        return Optional.of(new LocalSkillResult(route, executionDetails, fallbackAnswer, detailed));
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public record LocalSkillResult(String route,
                                   String executionDetails,
                                   String fallbackAnswer,
                                   boolean detailed) {
    }
}
