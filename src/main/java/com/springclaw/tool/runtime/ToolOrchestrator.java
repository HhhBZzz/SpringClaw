package com.springclaw.tool.runtime;

import com.springclaw.service.skill.SkillService;
import com.springclaw.service.agent.AgentDecision;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具编排器。
 *
 * 设计说明：
 * 1. 由运行时按请求语义动态挑选工具包，避免固定全量暴露。
 * 2. 新增工具包只需新增 AgentToolProvider bean，不影响 ChatService 主流程。
 */
@Component
public class ToolOrchestrator {

    private final SkillService skillService;
    private final List<AgentToolProvider> providers;

    @Autowired
    public ToolOrchestrator(SkillService skillService,
                            List<AgentToolProvider> providers) {
        this.skillService = skillService;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

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
        this.skillService = skillService;
        this.providers = AgentToolProviderConfig.legacyProviders(
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
                fileTriggerKeywords,
                localFileTriggerKeywords,
                workspaceTriggerKeywords,
                webTriggerKeywords,
                weatherTriggerKeywords,
                exchangeTriggerKeywords,
                newsTriggerKeywords,
                scriptTriggerKeywords
        );
    }

    public Object[] selectTools(String channel,
                                String userId,
                                String userMessage,
                                String planText) {
        String merged = mergeText(userMessage, planText);
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        return providers.stream()
                .filter(provider -> provider.isAllowed(allowedToolPacks))
                .filter(provider -> provider.matches(merged))
                .map(AgentToolProvider::tool)
                .toArray();
    }

    public Object[] selectAgentTools(String channel, String userId) {
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        return providers.stream()
                .filter(provider -> provider.includeForAgentMode())
                .filter(provider -> provider.isAllowed(allowedToolPacks))
                .map(AgentToolProvider::tool)
                .toArray();
    }

    public Object[] selectAgentTools(String channel, String userId, AgentDecision decision) {
        if (decision == null || decision.isGeneral()) {
            return new Object[0];
        }
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        Set<String> capabilityIds = decision.selectedCapabilities() == null
                ? Set.of()
                : decision.selectedCapabilities().stream()
                .map(this::normalizeCapability)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return providers.stream()
                .filter(provider -> provider.includeForAgentMode())
                .filter(provider -> provider.isAllowed(allowedToolPacks))
                .filter(provider -> capabilityIds.isEmpty() || capabilityIds.contains(normalizeCapability(provider.id())))
                .map(AgentToolProvider::tool)
                .toArray();
    }

    public Object[] selectTools(String channel,
                                String userId,
                                String userMessage,
                                String planText,
                                AgentDecision decision) {
        if (decision == null) {
            return selectTools(channel, userId, userMessage, planText);
        }
        String merged = mergeText(userMessage, planText);
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        Set<String> capabilityIds = decision.selectedCapabilities() == null
                ? Set.of()
                : decision.selectedCapabilities().stream()
                .map(this::normalizeCapability)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return providers.stream()
                .filter(provider -> provider.isAllowed(allowedToolPacks))
                .filter(provider -> capabilityIds.isEmpty() || capabilityIds.contains(normalizeCapability(provider.id())))
                .filter(provider -> provider.matches(merged) || capabilityIds.contains(normalizeCapability(provider.id())))
                .map(AgentToolProvider::tool)
                .toArray();
    }

    private String mergeText(String userMessage, String planText) {
        return ((userMessage == null ? "" : userMessage)
                + " "
                + (planText == null ? "" : planText))
                .toLowerCase();
    }

    private String normalizeCapability(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
