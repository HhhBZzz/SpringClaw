package com.springclaw.service.agent;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fast deterministic router used before any optional model-based classification.
 */
@Service
public class AgentDecisionRouter {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final SkillRegistryService skillRegistryService;
    private final ToolRiskPolicyService riskPolicyService;
    private final CapabilityRegistry capabilityRegistry;

    public AgentDecisionRouter(SkillRegistryService skillRegistryService,
                               ToolRiskPolicyService riskPolicyService,
                               CapabilityRegistry capabilityRegistry) {
        this.skillRegistryService = skillRegistryService;
        this.riskPolicyService = riskPolicyService;
        this.capabilityRegistry = capabilityRegistry;
    }

    public AgentDecision routeByRules(AgentDecisionRequest request) {
        String question = request == null ? "" : safe(request.question()).trim();
        if (!StringUtils.hasText(question)) {
            return AgentDecision.clarify("用户输入为空，需要补充目标。");
        }
        String lower = question.toLowerCase(Locale.ROOT);
        String risk = riskPolicyService.classifyRisk(question);
        if ("dangerous".equals(risk)) {
            return new AgentDecision("unknown", "ask_clarification", List.of("dangerous-action"), risk, true,
                    "请求包含高风险命令或删除类操作，必须进入确认/拒绝流程。");
        }
        if (looksLikeScheduledTask(lower)) {
            return new AgentDecision("scheduled_task", "task_draft", List.of("scheduled-task"), risk, true,
                    "检测到定时/周期执行意图，先生成任务草稿并等待确认。");
        }
        if (looksAmbiguousAction(lower)) {
            return AgentDecision.clarify("用户目标偏动作化但缺少对象，进入澄清或轻量模型分类。");
        }
        Set<String> allowedToolPacks = request == null ? Set.of() : request.allowedToolPacks();

        // 使用 CapabilityRegistry 匹配能力（替代硬编码关键词分类）
        if (capabilityRegistry != null) {
            List<CapabilityRegistry.CapabilityEntry> matchedCapabilities = capabilityRegistry.findByTriggerKeywords(question);
            if (!matchedCapabilities.isEmpty()) {
                CapabilityRegistry.CapabilityEntry best = matchedCapabilities.get(0);
                return buildDecisionFromCapability(best, question, risk, allowedToolPacks);
            }
        }

        Optional<SkillDefinition> highConfidenceSkill = skillRegistryService.matchHighConfidenceDefinition(question)
                .filter(skill -> skill.matchesAllowedToolPacks(allowedToolPacks));
        if (highConfidenceSkill.isPresent()) {
            return buildDecisionFromSkill(highConfidenceSkill.get(), risk);
        }

        // Skill 注册表匹配兜底
        Optional<SkillDefinition> matchedSkill = skillRegistryService.matchBestAgentVisibleDefinition(question, allowedToolPacks);
        if (matchedSkill.isPresent()) {
            return buildDecisionFromSkill(matchedSkill.get(), risk);
        }

        return AgentDecision.general("未命中外部能力需求，走普通模型回答最短路径。");
    }

    /** 从 CapabilityRegistry 匹配结果构建 AgentDecision */
    private AgentDecision buildDecisionFromCapability(CapabilityRegistry.CapabilityEntry entry,
                                                       String question, String risk, Set<String> allowedToolPacks) {
        String toolset = entry.toolset();
        String intent = intentForToolset(toolset);
        String executionPath = "agent_tools";
        List<String> capabilities = capabilitiesForToolset(toolset, question);

        return new AgentDecision(intent, executionPath, capabilities,
                entry.riskLevel(), riskPolicyService.requiresConfirmation(entry.riskLevel()),
                "匹配到能力: " + entry.id() + "（" + entry.description() + "）");
    }

    private AgentDecision buildDecisionFromSkill(SkillDefinition skill, String risk) {
        if (isLocalFilesSkill(skill.skillId()) || containsToolPack(skill, "file") && !containsToolPack(skill, "workspace")) {
            return new AgentDecision("local_files", "agent_tools", List.of("local-files", "file"), risk,
                    riskPolicyService.requiresConfirmation(risk), "匹配到本地文件能力，走授权目录读取链路。");
        }
        if (containsToolPack(skill, "workspace")) {
            return new AgentDecision("workspace_analysis", "agent_tools",
                    List.of("workspace-search", "workspace-review", "file", "skill-library"),
                    risk,
                    riskPolicyService.requiresConfirmation(risk),
                    "匹配到 workspace skill: " + skill.skillId());
        }
        List<String> capabilities = List.of("script-skill", "skill-library", skill.skillId());
        return new AgentDecision("skill_task", "skill_direct", capabilities, risk, riskPolicyService.requiresConfirmation(risk),
                "匹配到 skill: " + skill.skillId());
    }

    private String intentForToolset(String toolset) {
        return switch (toolset) {
            case "system" -> "model_control";
            case "file" -> "local_files";
            case "workspace" -> "workspace_analysis";
            case "web" -> "web_research";
            case "script" -> "skill_task";
            default -> "general";
        };
    }

    private List<String> capabilitiesForToolset(String toolset, String question) {
        return switch (toolset) {
            case "system" -> List.of("system", "skill-library");
            case "file" -> List.of("local-files", "file");
            case "workspace" -> List.of("workspace-search", "workspace-review", "file", "skill-library");
            case "web" -> webCapabilities(question.toLowerCase(Locale.ROOT));
            case "script" -> List.of("script-skill", "skill-library");
            default -> List.of();
        };
    }

    private boolean looksLikeScheduledTask(String lower) {
        return containsAny(lower, "定时", "任务", "每天", "每周", "每月", "cron", "提醒我", "定期")
                && containsAny(lower, "每天", "每周", "每月", "cron", "定时", "定期", "9 点", "9点", "提醒");
    }

    private List<String> webCapabilities(String lower) {
        List<String> capabilities = new ArrayList<>();
        if (containsAny(lower, "天气", "气温", "温度", "下雨", "weather", "forecast")) {
            capabilities.add("weather");
        }
        if (containsAny(lower, "新闻", "news")) {
            capabilities.add("news");
        }
        if (containsAny(lower, "汇率", "exchange", "usd", "cny", "eur", "jpy", "美元", "人民币", "欧元", "日元")) {
            capabilities.add("exchange");
        }
        if (capabilities.isEmpty()) {
            capabilities.add("web");
        }
        return List.copyOf(capabilities);
    }

    private boolean looksAmbiguousAction(String lower) {
        return containsAny(lower, "帮我处理", "弄一下", "搞一下", "操作一下", "自动帮我", "执行一下")
                && !containsAny(lower, "是什么", "解释", "为什么", "区别");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalFilesSkill(String skillId) {
        return "local-files".equalsIgnoreCase(safe(skillId))
                || "local_files".equalsIgnoreCase(safe(skillId));
    }

    private boolean containsToolPack(SkillDefinition skill, String toolPack) {
        if (skill == null || skill.toolPacks() == null || !StringUtils.hasText(toolPack)) {
            return false;
        }
        String normalized = toolPack.trim().toLowerCase(Locale.ROOT);
        return skill.toolPacks().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
