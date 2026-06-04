package com.springclaw.tool.runtime;

import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.FileToolPack;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.pack.SystemHealthToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Configuration
public class AgentToolProviderConfig {

    @Bean
    AgentToolProvider systemToolProvider(SystemToolPack toolPack) {
        return systemProvider(toolPack);
    }

    @Bean
    AgentToolProvider systemHealthToolProvider(SystemHealthToolPack toolPack) {
        return new DefaultAgentToolProvider("system-health", Set.of("system"), toolPack,
                text -> containsAnyLiteral(text,
                        "检查后端", "后端状态", "数据库", "redis", "rabbitmq", "模型配置",
                        "运行状态", "健康状态", "runtime health", "system health"));
    }

    @Bean
    AgentToolProvider fileToolProvider(FileToolPack toolPack,
                                       @Value("${springclaw.skill.file-trigger-keywords:文件,目录,path,read,write,list,保存,读取}") String keywords) {
        return fileProvider(toolPack, keywords);
    }

    @Bean
    AgentToolProvider localFilesystemToolProvider(LocalFilesystemToolPack toolPack,
                                                  @Value("${springclaw.skill.local-file-trigger-keywords:本地文件,电脑文件,授权文件,授权目录,本机文件,桌面,下载,文档,Desktop,Downloads,Documents,简历,论文}") String keywords) {
        return localFilesystemProvider(toolPack, keywords);
    }

    @Bean
    AgentToolProvider workspaceSearchToolProvider(WorkspaceSearchToolPack toolPack,
                                                  @Value("${springclaw.skill.workspace-trigger-keywords:找文件,搜代码,在哪个文件,关键词检索,不知道路径,search file,find file,grep}") String workspaceKeywords,
                                                  @Value("${springclaw.skill.file-trigger-keywords:文件,目录,path,read,write,list,保存,读取}") String fileKeywords) {
        return workspaceSearchProvider(toolPack, workspaceKeywords, fileKeywords);
    }

    @Bean
    AgentToolProvider workspaceReviewToolProvider(WorkspaceReviewToolPack toolPack) {
        return workspaceReviewProvider(toolPack);
    }

    @Bean
    AgentToolProvider webSearchToolProvider(WebSearchToolPack toolPack,
                                            @Value("${springclaw.skill.web-trigger-keywords:联网,搜索,查一下,网页,新闻,官网,web search,google,bing}") String keywords) {
        return keywordProvider("web", Set.of("web"), toolPack, keywords);
    }

    @Bean
    AgentToolProvider weatherToolProvider(WeatherToolPack toolPack,
                                          @Value("${springclaw.skill.weather-trigger-keywords:天气,气温,温度,下雨,weather}") String keywords) {
        return keywordProvider("weather", Set.of("weather"), toolPack, keywords);
    }

    @Bean
    AgentToolProvider exchangeRateToolProvider(ExchangeRateToolPack toolPack,
                                               @Value("${springclaw.skill.exchange-trigger-keywords:汇率,美元,人民币,欧元,exchange,usd,cny,eur}") String keywords) {
        return keywordProvider("exchange", Set.of("exchange"), toolPack, keywords);
    }

    @Bean
    AgentToolProvider newsToolProvider(NewsToolPack toolPack,
                                       @Value("${springclaw.skill.news-trigger-keywords:新闻,热点,头条,资讯,news}") String keywords) {
        return keywordProvider("news", Set.of("news"), toolPack, keywords);
    }

    @Bean
    AgentToolProvider skillLibraryToolProvider(SkillLibraryToolPack toolPack) {
        return skillLibraryProvider(toolPack);
    }

    @Bean
    AgentToolProvider scriptSkillToolProvider(ScriptSkillToolPack toolPack,
                                              @Value("${springclaw.skill.script-trigger-keywords:脚本,skill,执行技能,python,run skill}") String keywords) {
        return keywordProvider("script-skill", Set.of("script"), toolPack, keywords);
    }

    static List<AgentToolProvider> legacyProviders(FileToolPack fileToolPack,
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
                                                   String fileTriggerKeywords,
                                                   String localFileTriggerKeywords,
                                                   String workspaceTriggerKeywords,
                                                   String webTriggerKeywords,
                                                   String weatherTriggerKeywords,
                                                   String exchangeTriggerKeywords,
                                                   String newsTriggerKeywords,
                                                   String scriptTriggerKeywords) {
        return List.of(
                systemProvider(systemToolPack),
                fileProvider(fileToolPack, fileTriggerKeywords),
                localFilesystemProvider(localFilesystemToolPack, localFileTriggerKeywords),
                workspaceSearchProvider(workspaceSearchToolPack, workspaceTriggerKeywords, fileTriggerKeywords),
                workspaceReviewProvider(workspaceReviewToolPack),
                keywordProvider("web", Set.of("web"), webSearchToolPack, webTriggerKeywords),
                keywordProvider("weather", Set.of("weather"), weatherToolPack, weatherTriggerKeywords),
                keywordProvider("exchange", Set.of("exchange"), exchangeRateToolPack, exchangeTriggerKeywords),
                keywordProvider("news", Set.of("news"), newsToolPack, newsTriggerKeywords),
                skillLibraryProvider(skillLibraryToolPack),
                keywordProvider("script-skill", Set.of("script"), scriptSkillToolPack, scriptTriggerKeywords)
        );
    }

    private static AgentToolProvider systemProvider(SystemToolPack toolPack) {
        return new DefaultAgentToolProvider("system", Set.of("system"), toolPack, text -> true);
    }

    private static AgentToolProvider fileProvider(FileToolPack toolPack, String keywords) {
        List<String> parsedKeywords = keywords(keywords);
        return new DefaultAgentToolProvider("file", Set.of("file"), toolPack,
                text -> containsAnyKeyword(text, parsedKeywords)
                        && (hasExplicitPath(text) || containsAnyLiteral(text,
                        "写入", "保存到", "覆盖", "读取", "read", "write", "list", "列出目录", "列出文件")));
    }

    private static AgentToolProvider localFilesystemProvider(LocalFilesystemToolPack toolPack, String keywords) {
        List<String> parsedKeywords = keywords(keywords);
        return new DefaultAgentToolProvider("local-files", Set.of("file"), toolPack,
                text -> containsAnyKeyword(text, parsedKeywords)
                        || containsAnyLiteral(text,
                        "本地电脑", "授权根目录", "授权目录", "授权文件", "电脑里", "电脑上",
                        "读取本地", "搜索本地", "找一下简历", "找简历", "找论文", "local filesystem"));
    }

    private static AgentToolProvider workspaceSearchProvider(WorkspaceSearchToolPack toolPack,
                                                             String workspaceKeywords,
                                                             String fileKeywords) {
        List<String> parsedWorkspaceKeywords = keywords(workspaceKeywords);
        List<String> parsedFileKeywords = keywords(fileKeywords);
        return new DefaultAgentToolProvider("workspace-search", Set.of("workspace"), toolPack,
                text -> containsAnyKeyword(text, parsedWorkspaceKeywords)
                        || looksLikeWorkspaceQuestion(text)
                        || (containsAnyKeyword(text, parsedFileKeywords) && !hasExplicitPath(text)));
    }

    private static AgentToolProvider workspaceReviewProvider(WorkspaceReviewToolPack toolPack) {
        return new DefaultAgentToolProvider("workspace-review", Set.of("workspace"), toolPack,
                text -> containsAnyLiteral(text,
                        "审查项目", "项目审查", "审查源码", "源码审查", "架构审查", "代码审查",
                        "review 项目", "review 代码", "冗余代码", "垃圾代码", "架构是否合理",
                        "哪里不合理", "哪里需要优化", "安全风险", "技术债", "code review"));
    }

    private static AgentToolProvider skillLibraryProvider(SkillLibraryToolPack toolPack) {
        return new DefaultAgentToolProvider("skill-library", Set.of("script"), toolPack,
                text -> containsAnyLiteral(text,
                        "有哪些 skill", "有哪些 skills", "skill 列表", "skills 列表", "查看 skill", "打开 skill",
                        "skill_view", "skills_list", "skills_status", "列出 skill", "列出 skills", "技能列表", "查看技能", "打开技能",
                        "skill 使用统计", "skill 使用情况", "技能使用统计", "技能使用情况", "最近使用 skill", "curator"));
    }

    private static AgentToolProvider keywordProvider(String id,
                                                     Set<String> requiredToolPacks,
                                                     Object toolPack,
                                                     String rawKeywords) {
        List<String> parsedKeywords = keywords(rawKeywords);
        return new DefaultAgentToolProvider(id, requiredToolPacks, toolPack,
                text -> containsAnyKeyword(text, parsedKeywords));
    }

    private static List<String> keywords(String csv) {
        return Arrays.stream((csv == null ? "" : csv).split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .toList();
    }

    private static boolean containsAnyKeyword(String text, List<String> keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeWorkspaceQuestion(String text) {
        return containsAnyLiteral(text,
                "项目", "代码库", "代码里", "源码", "实现", "怎么实现", "实现在哪",
                "类", "方法", "接口", "配置", "包", "哪个文件", "在哪个文件", "是否存在", "有没有",
                "spring ai", "springai", "spring-ai", "application.yml", "application.yaml",
                ".java", ".yml", ".yaml", ".xml", ".properties", "config");
    }

    private static boolean hasExplicitPath(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("/")
                || lower.contains("\\")
                || lower.contains("src/main/")
                || lower.contains("src/test/")
                || lower.contains("resources/");
    }

    private static boolean containsAnyLiteral(String text, String... literals) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String literal : literals) {
            if (lower.contains(literal.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
