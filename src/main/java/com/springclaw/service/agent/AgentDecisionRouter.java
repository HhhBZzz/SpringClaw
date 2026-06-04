package com.springclaw.service.agent;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
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

    public AgentDecisionRouter(SkillRegistryService skillRegistryService,
                               ToolRiskPolicyService riskPolicyService) {
        this.skillRegistryService = skillRegistryService;
        this.riskPolicyService = riskPolicyService;
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
        if (looksLikeModelControl(lower)) {
            return new AgentDecision("model_control", "agent_tools", List.of("system", "skill-library"), "read", false,
                    "检测到模型/运行状态查询，走受控本地控制面能力。");
        }
        if (looksLikeLocalFiles(lower)) {
            return new AgentDecision("local_files", "agent_tools", List.of("local-files", "file"), risk, riskPolicyService.requiresConfirmation(risk),
                    "检测到本地授权文件读取/搜索意图，只暴露本地文件相关能力。");
        }
        if (looksLikeWorkspace(lower)) {
            return new AgentDecision("workspace_analysis", "agent_tools", List.of("workspace-search", "workspace-review", "file", "skill-library"), risk, riskPolicyService.requiresConfirmation(risk),
                    "检测到项目/源码/架构分析意图，只暴露工作区和代码分析能力。");
        }
        if (looksLikeWeb(lower)) {
            return new AgentDecision("web_research", "agent_tools", webCapabilities(lower), risk, riskPolicyService.requiresConfirmation(risk),
                    "检测到网页/实时信息查询意图，只暴露外部查询类能力。");
        }
        Optional<SkillDefinition> matchedSkill = skillRegistryService.matchBestAgentVisibleDefinition(question, request == null ? Set.of() : request.allowedToolPacks());
        if (matchedSkill.map(skill -> isLocalFilesSkill(skill.skillId())).orElse(false)) {
            return new AgentDecision("local_files", "agent_tools", List.of("local-files", "file"), risk, riskPolicyService.requiresConfirmation(risk),
                    "匹配到本地文件能力，走授权目录读取链路。");
        }
        if (looksLikeSkill(lower) || matchedSkill.isPresent()) {
            List<String> capabilities = matchedSkill
                    .map(skill -> List.of("script-skill", "skill-library", skill.skillId()))
                    .orElse(List.of("script-skill", "skill-library"));
            return new AgentDecision("skill_task", "skill_direct", capabilities, risk, riskPolicyService.requiresConfirmation(risk),
                    matchedSkill.map(skill -> "匹配到 skill: " + skill.skillId()).orElse("检测到 skill/脚本执行意图。"));
        }
        if (looksAmbiguousAction(lower)) {
            return AgentDecision.clarify("用户目标偏动作化但缺少对象，进入澄清或轻量模型分类。");
        }
        return AgentDecision.general("未命中外部能力需求，走普通模型回答最短路径。");
    }

    private boolean looksLikeScheduledTask(String lower) {
        return containsAny(lower, "定时", "任务", "每天", "每周", "每月", "cron", "提醒我", "定期")
                && containsAny(lower, "每天", "每周", "每月", "cron", "定时", "定期", "9 点", "9点", "提醒");
    }

    private boolean looksLikeModelControl(String lower) {
        return containsAny(lower, "当前模型", "目前模型", "模型配置", "provider", "当前通道", "模型状态", "deepseek", "qwen")
                && containsAny(lower, "查看", "确认", "是什么", "状态", "配置", "可用");
    }

    private boolean looksLikeLocalFiles(String lower) {
        return containsAny(lower, "本地文件", "授权文件", "电脑文件", "电脑上", "桌面", "下载", "documents", "desktop", "简历", "论文", "ppt", "docx", "pdf")
                && containsAny(lower, "读取", "列出", "查找", "搜索", "找", "看", "看看", "查看", "有哪些", "有什么", "什么文件", "打开", "内容", "总结", "发给我");
    }

    private boolean looksLikeWorkspace(String lower) {
        return containsAny(lower, "项目", "源码", "代码", "架构", "模块", "类", "方法", "接口", "配置", "仓库", "springclaw")
                && containsAny(lower, "分析", "审查", "查看", "看看", "梳理", "实现", "结构", "在哪", "怎么做", "怎么执行", "执行", "流程", "链路", "优化", "冗余", "调用链", "agent");
    }

    private boolean looksLikeWeb(String lower) {
        return URL_PATTERN.matcher(lower).find()
                || containsAny(lower, "网页", "官网", "联网", "搜索", "新闻", "天气", "气温", "温度", "下雨", "weather", "forecast", "汇率", "爬取", "抓取", "http://", "https://");
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

    private boolean looksLikeSkill(String lower) {
        return containsAny(lower, "skill", "skills", "技能", "脚本", "运行", "执行")
                && containsAny(lower, "skill", "技能", "脚本", "repo_inspector", "web_crawler", "pdf_generator", "ppt_generator");
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

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
