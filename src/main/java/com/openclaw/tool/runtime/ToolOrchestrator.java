package com.openclaw.tool.runtime;

import com.openclaw.service.skill.SkillService;
import com.openclaw.tool.pack.ExchangeRateToolPack;
import com.openclaw.tool.pack.FileToolPack;
import com.openclaw.tool.pack.NewsToolPack;
import com.openclaw.tool.pack.ScriptSkillToolPack;
import com.openclaw.tool.pack.SystemToolPack;
import com.openclaw.tool.pack.WebSearchToolPack;
import com.openclaw.tool.pack.WeatherToolPack;
import com.openclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具编排器。
 *
 * 设计说明：
 * 1. 由运行时按请求语义动态挑选工具包，避免固定全量暴露。
 * 2. 新增工具包只需在此注册，不影响 ChatService 主流程。
 */
@Component
public class ToolOrchestrator {

    private final FileToolPack fileToolPack;
    private final SystemToolPack systemToolPack;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final WebSearchToolPack webSearchToolPack;
    private final WeatherToolPack weatherToolPack;
    private final ExchangeRateToolPack exchangeRateToolPack;
    private final NewsToolPack newsToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;
    private final SkillService skillService;
    private final List<String> fileTriggerKeywords;
    private final List<String> workspaceTriggerKeywords;
    private final List<String> webTriggerKeywords;
    private final List<String> weatherTriggerKeywords;
    private final List<String> exchangeTriggerKeywords;
    private final List<String> newsTriggerKeywords;
    private final List<String> scriptTriggerKeywords;

    public ToolOrchestrator(FileToolPack fileToolPack,
                            SystemToolPack systemToolPack,
                            WorkspaceSearchToolPack workspaceSearchToolPack,
                            WebSearchToolPack webSearchToolPack,
                            WeatherToolPack weatherToolPack,
                            ExchangeRateToolPack exchangeRateToolPack,
                            NewsToolPack newsToolPack,
                            ScriptSkillToolPack scriptSkillToolPack,
                            SkillService skillService,
                            @Value("${openclaw.skill.file-trigger-keywords:文件,目录,path,read,write,list,保存,读取}") String fileTriggerKeywords,
                            @Value("${openclaw.skill.workspace-trigger-keywords:找文件,搜代码,在哪个文件,关键词检索,不知道路径,search file,find file,grep}") String workspaceTriggerKeywords,
                            @Value("${openclaw.skill.web-trigger-keywords:联网,搜索,查一下,网页,新闻,官网,web search,google,bing}") String webTriggerKeywords,
                            @Value("${openclaw.skill.weather-trigger-keywords:天气,气温,温度,下雨,weather}") String weatherTriggerKeywords,
                            @Value("${openclaw.skill.exchange-trigger-keywords:汇率,美元,人民币,欧元,exchange,usd,cny,eur}") String exchangeTriggerKeywords,
                            @Value("${openclaw.skill.news-trigger-keywords:新闻,热点,头条,资讯,news}") String newsTriggerKeywords,
                            @Value("${openclaw.skill.script-trigger-keywords:脚本,skill,执行技能,python,run skill}") String scriptTriggerKeywords) {
        this.fileToolPack = fileToolPack;
        this.systemToolPack = systemToolPack;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.newsToolPack = newsToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.skillService = skillService;
        this.fileTriggerKeywords = Arrays.stream(fileTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.workspaceTriggerKeywords = Arrays.stream(workspaceTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.webTriggerKeywords = Arrays.stream(webTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.weatherTriggerKeywords = Arrays.stream(weatherTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.exchangeTriggerKeywords = Arrays.stream(exchangeTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.newsTriggerKeywords = Arrays.stream(newsTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.scriptTriggerKeywords = Arrays.stream(scriptTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
    }

    public Object[] selectTools(String channel,
                                String userId,
                                String userMessage,
                                String planText) {
        String merged = ((userMessage == null ? "" : userMessage) + " " + (planText == null ? "" : planText)).toLowerCase();
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        List<Object> tools = new ArrayList<>();

        if (allowedToolPacks.contains("system")) {
            tools.add(systemToolPack);
        }
        if (allowedToolPacks.contains("file") && needFileTools(merged)) {
            tools.add(fileToolPack);
        }
        if (allowedToolPacks.contains("workspace") && needWorkspaceTools(merged)) {
            tools.add(workspaceSearchToolPack);
        }
        if (allowedToolPacks.contains("web") && needWebTools(merged)) {
            tools.add(webSearchToolPack);
        }
        if (allowedToolPacks.contains("weather") && needWeatherTools(merged)) {
            tools.add(weatherToolPack);
        }
        if (allowedToolPacks.contains("exchange") && needExchangeTools(merged)) {
            tools.add(exchangeRateToolPack);
        }
        if (allowedToolPacks.contains("news") && needNewsTools(merged)) {
            tools.add(newsToolPack);
        }
        if (allowedToolPacks.contains("script") && needScriptTools(merged)) {
            tools.add(scriptSkillToolPack);
        }

        return tools.toArray();
    }

    public Object[] selectAgentTools(String channel, String userId) {
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        List<Object> tools = new ArrayList<>();

        if (allowedToolPacks.contains("system")) {
            tools.add(systemToolPack);
        }
        if (allowedToolPacks.contains("file")) {
            tools.add(fileToolPack);
        }
        if (allowedToolPacks.contains("workspace")) {
            tools.add(workspaceSearchToolPack);
        }
        if (allowedToolPacks.contains("web")) {
            tools.add(webSearchToolPack);
        }
        if (allowedToolPacks.contains("weather")) {
            tools.add(weatherToolPack);
        }
        if (allowedToolPacks.contains("exchange")) {
            tools.add(exchangeRateToolPack);
        }
        if (allowedToolPacks.contains("news")) {
            tools.add(newsToolPack);
        }
        if (allowedToolPacks.contains("script")) {
            tools.add(scriptSkillToolPack);
        }

        return tools.toArray();
    }

    private boolean needFileTools(String text) {
        if (!containsAnyKeyword(text, fileTriggerKeywords)) {
            return false;
        }
        return hasExplicitPath(text) || containsAnyLiteral(text,
                "写入", "保存到", "覆盖", "读取", "read", "write", "list", "列出目录", "列出文件");
    }

    private boolean needWorkspaceTools(String text) {
        return containsAnyKeyword(text, workspaceTriggerKeywords)
                || looksLikeWorkspaceQuestion(text)
                || (containsAnyKeyword(text, fileTriggerKeywords) && !hasExplicitPath(text));
    }

    private boolean needWebTools(String text) {
        return containsAnyKeyword(text, webTriggerKeywords);
    }

    private boolean needWeatherTools(String text) {
        return containsAnyKeyword(text, weatherTriggerKeywords);
    }

    private boolean needExchangeTools(String text) {
        return containsAnyKeyword(text, exchangeTriggerKeywords);
    }

    private boolean needNewsTools(String text) {
        return containsAnyKeyword(text, newsTriggerKeywords);
    }

    private boolean needScriptTools(String text) {
        return containsAnyKeyword(text, scriptTriggerKeywords);
    }

    private boolean containsAnyKeyword(String text, List<String> keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeWorkspaceQuestion(String text) {
        return containsAnyLiteral(text,
                "项目", "代码库", "代码里", "源码", "实现", "怎么实现", "实现在哪",
                "类", "方法", "接口", "配置", "包", "哪个文件", "在哪个文件", "是否存在", "有没有",
                "spring ai", "springai", "spring-ai", "application.yml", "application.yaml",
                ".java", ".yml", ".yaml", ".xml", ".properties", "config");
    }

    private boolean hasExplicitPath(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("/")
                || text.contains("\\")
                || text.contains("src/main/")
                || text.contains("src/test/")
                || text.contains("resources/");
    }

    private boolean containsAnyLiteral(String text, String... literals) {
        for (String literal : literals) {
            if (text.contains(literal)) {
                return true;
            }
        }
        return false;
    }
}
