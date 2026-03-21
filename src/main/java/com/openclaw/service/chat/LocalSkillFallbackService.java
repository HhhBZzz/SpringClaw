package com.openclaw.service.chat;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.skill.impl.BuiltinSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillDefinition;
import com.openclaw.tool.pack.ExchangeRateToolPack;
import com.openclaw.tool.pack.NewsToolPack;
import com.openclaw.tool.pack.ScriptSkillToolPack;
import com.openclaw.tool.pack.SystemToolPack;
import com.openclaw.tool.pack.WebSearchToolPack;
import com.openclaw.tool.pack.WeatherToolPack;
import com.openclaw.tool.pack.WorkspaceSearchToolPack;
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

    LocalSkillFallbackService(boolean enabled,
                              SystemToolPack systemToolPack,
                              WorkspaceSearchToolPack workspaceSearchToolPack,
                              WebSearchToolPack webSearchToolPack,
                              WeatherToolPack weatherToolPack,
                              ExchangeRateToolPack exchangeRateToolPack,
                              NewsToolPack newsToolPack,
                              ScriptSkillToolPack scriptSkillToolPack,
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
                new BuiltinSkillExecutionService(
                        new BuiltinSkillCatalogService(),
                        workspaceSearchToolPack,
                        scriptSkillToolPack,
                        scriptSkillCatalogService
                ),
                scriptSkillCatalogService,
                aiProviderService);
    }

    @Autowired
    public LocalSkillFallbackService(@Value("${openclaw.chat.local-fallback-enabled:true}") boolean enabled,
                                     SystemToolPack systemToolPack,
                                     WorkspaceSearchToolPack workspaceSearchToolPack,
                                     WebSearchToolPack webSearchToolPack,
                                     WeatherToolPack weatherToolPack,
                                     ExchangeRateToolPack exchangeRateToolPack,
                                     NewsToolPack newsToolPack,
                                     ScriptSkillToolPack scriptSkillToolPack,
                                     BuiltinSkillExecutionService builtinSkillExecutionService,
                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                     AiProviderService aiProviderService) {
        this.enabled = enabled;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.newsToolPack = newsToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.builtinSkillExecutionService = builtinSkillExecutionService;
        this.modelControlSupport = new LocalSkillModelControlSupport(systemToolPack, aiProviderService);
        this.querySupport = new LocalSkillQuerySupport(scriptSkillToolPack, scriptSkillCatalogService);
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

        if (querySupport.looksLikeWorkspaceQuestion(lower)) {
            String answer = workspaceSearchToolPack.analyzeWorkspaceTask(q);
            if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
                String skillAnswer = querySupport.runScriptSkillByCategory("workspace", q);
                if (StringUtils.hasText(skillAnswer)) {
                    return localResult("WORKSPACE_ANALYSIS", skillAnswer, skillAnswer, true);
                }
            }
            return localResult("WORKSPACE_ANALYSIS", answer, answer, true);
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
