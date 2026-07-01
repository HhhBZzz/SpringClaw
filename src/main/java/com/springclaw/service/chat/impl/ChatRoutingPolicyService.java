package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 聊天链路路由策略。
 * 目标：默认走 simplified，复杂任务自动升级到 opar，只有管理员/开发者可手动覆盖。
 */
@Service
public class ChatRoutingPolicyService {

    private static final List<String> FORCE_OPAR_PREFIXES = List.of(
            "深度分析：", "深度分析:", "深度分析 ",
            "详细分析：", "详细分析:", "详细分析 ",
            "深度模式：", "深度模式:", "深度模式 "
    );
    private static final List<String> FORCE_SIMPLIFIED_PREFIXES = List.of(
            "普通回答：", "普通回答:", "普通回答 ",
            "普通模式：", "普通模式:", "普通模式 ",
            "快速回答：", "快速回答:", "快速回答 ",
            "直接回答：", "直接回答:", "直接回答 "
    );

    private final SkillRegistryService skillRegistryService;
    private final CapabilityRegistry capabilityRegistry;

    public ChatRoutingPolicyService(SkillRegistryService skillRegistryService) {
        this(skillRegistryService, null);
    }

    @Autowired
    public ChatRoutingPolicyService(SkillRegistryService skillRegistryService,
                                     CapabilityRegistry capabilityRegistry) {
        this.skillRegistryService = skillRegistryService;
        this.capabilityRegistry = capabilityRegistry;
    }

    public RoutingDecision decide(String question,
                                  String roleCode,
                                  String defaultMode,
                                  boolean autoUpgradeEnabled,
                                  Set<String> allowedToolPacks) {
        return decide(question, roleCode, defaultMode, autoUpgradeEnabled, allowedToolPacks, null);
    }

    public RoutingDecision decide(String question,
                                  String roleCode,
                                  String defaultMode,
                                  boolean autoUpgradeEnabled,
                                  Set<String> allowedToolPacks,
                                  String responseMode) {
        String normalizedQuestion = StringUtils.hasText(question) ? question.trim() : "";
        String normalizedRole = normalizeRole(roleCode);
        String normalizedDefaultMode = normalizeMode(defaultMode);
        String normalizedResponseMode = normalizeResponseMode(responseMode);
        String intent = detectIntent(normalizedQuestion, allowedToolPacks);

        if ("fast".equals(normalizedResponseMode)) {
            PrefixMatch stripped = stripAnyModePrefix(normalizedQuestion);
            return new RoutingDecision(
                    stripped.content(),
                    "simplified",
                    true,
                    false,
                    "用户显式选择快速模式，使用轻量链路。",
                    normalizedResponseMode,
                    intent
            );
        }

        if ("tool".equals(normalizedResponseMode)) {
            PrefixMatch stripped = stripAnyModePrefix(normalizedQuestion);
            return new RoutingDecision(
                    stripped.content(),
                    "simplified",
                    true,
                    false,
                    "用户显式选择工具优先模式，使用轻量链路并优先暴露工具能力。",
                    normalizedResponseMode,
                    "tool:" + intent
            );
        }

        if ("deep".equals(normalizedResponseMode)) {
            PrefixMatch stripped = stripAnyModePrefix(normalizedQuestion);
            return new RoutingDecision(
                    stripped.content(),
                    "opar",
                    true,
                    false,
                    "用户显式选择深度模式，使用 OPAR 链路。",
                    normalizedResponseMode,
                    intent
            );
        }

        PrefixMatch forceOpar = stripPrefix(normalizedQuestion, FORCE_OPAR_PREFIXES);
        if (forceOpar.matched()) {
            if (canManuallyOverride(normalizedRole)) {
                return new RoutingDecision(
                        forceOpar.content(),
                        "opar",
                        true,
                        false,
                        "用户显式要求深度分析，当前角色允许手动切换。",
                        "deep",
                        detectIntent(forceOpar.content(), allowedToolPacks)
                );
            }
            return new RoutingDecision(
                    forceOpar.content(),
                    normalizedDefaultMode,
                    false,
                    false,
                    "检测到深度分析前缀，但当前角色无手动切换权限，继续使用默认链路。",
                    normalizedResponseMode,
                    detectIntent(forceOpar.content(), allowedToolPacks)
            );
        }

        PrefixMatch forceSimplified = stripPrefix(normalizedQuestion, FORCE_SIMPLIFIED_PREFIXES);
        if (forceSimplified.matched()) {
            return new RoutingDecision(
                    forceSimplified.content(),
                    "simplified",
                    canManuallyOverride(normalizedRole),
                    false,
                    "用户显式要求快速/普通回答，降级到轻量链路。",
                    "fast",
                    detectIntent(forceSimplified.content(), allowedToolPacks)
            );
        }

        if ("simplified".equals(normalizedDefaultMode) && autoUpgradeEnabled && shouldAutoUpgrade(normalizedQuestion, allowedToolPacks)) {
            return new RoutingDecision(
                    normalizedQuestion,
                    "opar",
                    false,
                    true,
                    "命中复杂任务特征，自动升级到深度分析链路。",
                    normalizedResponseMode,
                    intent
            );
        }

        if ("agent".equals(normalizedResponseMode)
                && "opar".equals(normalizedDefaultMode)
                && "general".equals(intent)) {
            return new RoutingDecision(
                    normalizedQuestion,
                    "simplified",
                    false,
                    false,
                    "普通 Agent 问答保持轻量链路，避免全局深度模式劫持基础对话。",
                    normalizedResponseMode,
                    intent
            );
        }

        return new RoutingDecision(
                normalizedQuestion,
                normalizedDefaultMode,
                false,
                false,
                "使用当前默认链路。",
                normalizedResponseMode,
                intent
        );
    }

    boolean shouldAutoUpgrade(String question, Set<String> allowedToolPacks) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        if (skillRegistryService.matchHighConfidenceDefinition(question)
                .filter(definition -> definition.matchesAllowedToolPacks(allowedToolPacks))
                .filter(definition -> definition.enabled()
                        && "opar".equalsIgnoreCase(definition.preferredMode()))
                .isPresent()) {
            return true;
        }
        if (skillRegistryService.matchBestAgentVisibleDefinition(question, allowedToolPacks)
                .filter(definition -> definition.enabled()
                        && "opar".equalsIgnoreCase(definition.preferredMode()))
                .isPresent()) {
            return true;
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        int score = 0;
        if (TextUtils.containsAny(normalized, "分析", "排查", "定位", "修复", "对比", "比较", "梳理", "拆解", "设计", "审查")) {
            score++;
        }
        if (TextUtils.containsAny(normalized, "日志", "报错", "异常", "堆栈", "代码", "项目", "配置", "启动", "调用链", "接口", "类", "文件", "sql", "redis", "rabbitmq")) {
            score++;
        }
        if (TextUtils.containsAny(normalized, "先", "再", "然后", "同时", "并且", "分别", "逐步", "一步一步", "最后")) {
            score++;
        }
        if (normalized.length() >= 28) {
            score++;
        }
        if (normalized.contains("\n") || normalized.contains("```")) {
            score++;
        }
        return score >= 3;
    }

    private PrefixMatch stripPrefix(String question, List<String> prefixes) {
        if (!StringUtils.hasText(question)) {
            return new PrefixMatch(false, "");
        }
        for (String prefix : prefixes) {
            if (question.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String stripped = question.substring(prefix.length()).trim();
                return new PrefixMatch(true, StringUtils.hasText(stripped) ? stripped : question.trim());
            }
        }
        return new PrefixMatch(false, question);
    }

    private PrefixMatch stripAnyModePrefix(String question) {
        PrefixMatch deep = stripPrefix(question, FORCE_OPAR_PREFIXES);
        if (deep.matched()) {
            return deep;
        }
        PrefixMatch fast = stripPrefix(question, FORCE_SIMPLIFIED_PREFIXES);
        return fast.matched() ? fast : new PrefixMatch(false, StringUtils.hasText(question) ? question.trim() : "");
    }

    private boolean canManuallyOverride(String roleCode) {
        return "ADMIN".equals(roleCode) || "DEVELOPER".equals(roleCode);
    }


    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "simplified";
        }
        return "opar".equalsIgnoreCase(mode.trim()) ? "opar" : "simplified";
    }

    private String normalizeResponseMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "agent";
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "fast", "deep", "tool" -> mode.trim().toLowerCase(Locale.ROOT);
            default -> "agent";
        };
    }

    private String detectIntent(String question, Set<String> allowedToolPacks) {
        if (!StringUtils.hasText(question)) {
            return "general";
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);

        // 优先使用 CapabilityRegistry 进行意图检测（数据驱动）
        if (capabilityRegistry != null) {
            List<CapabilityRegistry.CapabilityEntry> matches = capabilityRegistry.findByTriggerKeywords(question);
            if (!matches.isEmpty()) {
                return switch (matches.get(0).toolset()) {
                    case "system" -> "control-plane";
                    case "file" -> "local-files";
                    case "workspace" -> "workspace-analysis";
                    case "web" -> "web-research";
                    case "script" -> "tool-skill";
                    default -> "general";
                };
            }
        }

        // Registry 不可用时只保留结构特征兜底，避免旧业务关键词绕过能力注册表。
        if (TextUtils.containsAny(normalized, "http://", "https://")) {
            return "web-research";
        }
        if (allowedToolPacks != null && allowedToolPacks.contains("script")
                && skillRegistryService.matchBestAgentVisibleDefinition(question, allowedToolPacks).isPresent()) {
            return "tool-skill";
        }
        return "general";
    }

    private String normalizeRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return "USER";
        }
        return roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private record PrefixMatch(boolean matched, String content) {
    }

    public record RoutingDecision(String effectiveQuestion,
                                  String executionMode,
                                  boolean manualOverride,
                                  boolean autoUpgraded,
                                  String reason,
                                  String responseMode,
                                  String intent) {
        public RoutingDecision(String effectiveQuestion,
                               String executionMode,
                               boolean manualOverride,
                               boolean autoUpgraded,
                               String reason) {
            this(effectiveQuestion, executionMode, manualOverride, autoUpgraded, reason, "agent", "general");
        }
    }
}
