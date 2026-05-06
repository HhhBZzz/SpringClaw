package com.springclaw.tool.runtime;

import com.springclaw.service.skill.SkillService;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.FileToolPack;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
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
    private final LocalFilesystemToolPack localFilesystemToolPack;
    private final SystemToolPack systemToolPack;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final WorkspaceReviewToolPack workspaceReviewToolPack;
    private final WebSearchToolPack webSearchToolPack;
    private final WeatherToolPack weatherToolPack;
    private final ExchangeRateToolPack exchangeRateToolPack;
    private final NewsToolPack newsToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;
    private final SkillLibraryToolPack skillLibraryToolPack;
    private final SkillService skillService;
    private final List<String> fileTriggerKeywords;
    private final List<String> localFileTriggerKeywords;
    private final List<String> workspaceTriggerKeywords;
    private final List<String> webTriggerKeywords;
    private final List<String> weatherTriggerKeywords;
    private final List<String> exchangeTriggerKeywords;
    private final List<String> newsTriggerKeywords;
    private final List<String> scriptTriggerKeywords;

    public ToolOrchestrator(FileToolPack fileToolPack,
                            LocalFilesystemToolPack localFilesystemToolPack,
                            SystemToolPack systemToolPack,
                            WorkspaceSearchToolPack workspaceSearchToolPack,
                            WorkspaceReviewToolPack workspaceReviewToolPack,
                            WebSearchToolPack webSearchToolPack,
                            WeatherToolPack weatherToolPack,
                            ExchangeRateToolPack exchangeRateToolPack,
                            NewsToolPack newsToolPack,
                            ScriptSkillToolPack scriptSkillToolPack,
                            SkillLibraryToolPack skillLibraryToolPack,
                            SkillService skillService,
                            @Value("${springclaw.skill.file-trigger-keywords:文件,目录,path,read,write,list,保存,读取}") String fileTriggerKeywords,
                            @Value("${springclaw.skill.local-file-trigger-keywords:本地文件,电脑文件,授权文件,授权目录,本机文件,桌面,下载,文档,Desktop,Downloads,Documents,简历,论文}") String localFileTriggerKeywords,
                            @Value("${springclaw.skill.workspace-trigger-keywords:找文件,搜代码,在哪个文件,关键词检索,不知道路径,search file,find file,grep}") String workspaceTriggerKeywords,
                            @Value("${springclaw.skill.web-trigger-keywords:联网,搜索,查一下,网页,新闻,官网,web search,google,bing}") String webTriggerKeywords,
                            @Value("${springclaw.skill.weather-trigger-keywords:天气,气温,温度,下雨,weather}") String weatherTriggerKeywords,
                            @Value("${springclaw.skill.exchange-trigger-keywords:汇率,美元,人民币,欧元,exchange,usd,cny,eur}") String exchangeTriggerKeywords,
                            @Value("${springclaw.skill.news-trigger-keywords:新闻,热点,头条,资讯,news}") String newsTriggerKeywords,
                            @Value("${springclaw.skill.script-trigger-keywords:脚本,skill,执行技能,python,run skill}") String scriptTriggerKeywords) {
        this.fileToolPack = fileToolPack;
        this.localFilesystemToolPack = localFilesystemToolPack;
        this.systemToolPack = systemToolPack;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.workspaceReviewToolPack = workspaceReviewToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.newsToolPack = newsToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.skillLibraryToolPack = skillLibraryToolPack;
        this.skillService = skillService;
        this.fileTriggerKeywords = Arrays.stream(fileTriggerKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
        this.localFileTriggerKeywords = Arrays.stream(localFileTriggerKeywords.split(","))
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
        if (allowedToolPacks.contains("file") && needLocalFilesystemTools(merged)) {
            tools.add(localFilesystemToolPack);
        }
        if (allowedToolPacks.contains("workspace") && needWorkspaceTools(merged)) {
            tools.add(workspaceSearchToolPack);
        }
        if (allowedToolPacks.contains("workspace") && needWorkspaceReview(merged)) {
            tools.add(workspaceReviewToolPack);
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
        if (allowedToolPacks.contains("script") && needSkillLibraryTools(merged)) {
            tools.add(skillLibraryToolPack);
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
            tools.add(localFilesystemToolPack);
        }
        if (allowedToolPacks.contains("workspace")) {
            tools.add(workspaceSearchToolPack);
            tools.add(workspaceReviewToolPack);
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
            tools.add(skillLibraryToolPack);
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

    private boolean needLocalFilesystemTools(String text) {
        return containsAnyKeyword(text, localFileTriggerKeywords)
                || containsAnyLiteral(text,
                "本地电脑", "授权根目录", "授权目录", "授权文件", "电脑里", "电脑上",
                "读取本地", "搜索本地", "找一下简历", "找简历", "找论文", "local filesystem");
    }

    private boolean needWorkspaceTools(String text) {
        return containsAnyKeyword(text, workspaceTriggerKeywords)
                || looksLikeWorkspaceQuestion(text)
                || (containsAnyKeyword(text, fileTriggerKeywords) && !hasExplicitPath(text));
    }

    private boolean needWorkspaceReview(String text) {
        return containsAnyLiteral(text,
                "审查项目", "项目审查", "审查源码", "源码审查", "架构审查", "代码审查",
                "review 项目", "review 代码", "冗余代码", "垃圾代码", "架构是否合理",
                "哪里不合理", "哪里需要优化", "安全风险", "技术债", "code review");
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

    private boolean needSkillLibraryTools(String text) {
        return containsAnyLiteral(text,
                "有哪些 skill", "有哪些 skills", "skill 列表", "skills 列表", "查看 skill", "打开 skill",
                "skill_view", "skills_list", "skills_status", "列出 skill", "列出 skills", "技能列表", "查看技能", "打开技能",
                "skill 使用统计", "skill 使用情况", "技能使用统计", "技能使用情况", "最近使用 skill", "curator");
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
