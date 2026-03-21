package com.openclaw.service.chat.impl;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

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

    public RoutingDecision decide(String question,
                                  String roleCode,
                                  String defaultMode,
                                  boolean autoUpgradeEnabled) {
        String normalizedQuestion = StringUtils.hasText(question) ? question.trim() : "";
        String normalizedRole = normalizeRole(roleCode);
        String normalizedDefaultMode = normalizeMode(defaultMode);

        PrefixMatch forceOpar = stripPrefix(normalizedQuestion, FORCE_OPAR_PREFIXES);
        if (forceOpar.matched()) {
            if (canManuallyOverride(normalizedRole)) {
                return new RoutingDecision(
                        forceOpar.content(),
                        "opar",
                        true,
                        false,
                        "用户显式要求深度分析，当前角色允许手动切换。"
                );
            }
            return new RoutingDecision(
                    forceOpar.content(),
                    normalizedDefaultMode,
                    false,
                    false,
                    "检测到深度分析前缀，但当前角色无手动切换权限，继续使用默认链路。"
            );
        }

        PrefixMatch forceSimplified = stripPrefix(normalizedQuestion, FORCE_SIMPLIFIED_PREFIXES);
        if (forceSimplified.matched()) {
            if (canManuallyOverride(normalizedRole)) {
                return new RoutingDecision(
                        forceSimplified.content(),
                        "simplified",
                        true,
                        false,
                        "用户显式要求普通回答，当前角色允许手动切换。"
                );
            }
            return new RoutingDecision(
                    forceSimplified.content(),
                    normalizedDefaultMode,
                    false,
                    false,
                    "检测到普通回答前缀，但当前角色无手动切换权限，继续使用默认链路。"
            );
        }

        if ("simplified".equals(normalizedDefaultMode) && autoUpgradeEnabled && shouldAutoUpgrade(normalizedQuestion)) {
            return new RoutingDecision(
                    normalizedQuestion,
                    "opar",
                    false,
                    true,
                    "命中复杂任务特征，自动升级到深度分析链路。"
            );
        }

        return new RoutingDecision(
                normalizedQuestion,
                normalizedDefaultMode,
                false,
                false,
                "使用当前默认链路。"
        );
    }

    boolean shouldAutoUpgrade(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        int score = 0;
        if (containsAny(normalized, "分析", "排查", "定位", "修复", "对比", "比较", "梳理", "拆解", "设计", "审查")) {
            score++;
        }
        if (containsAny(normalized, "日志", "报错", "异常", "堆栈", "代码", "项目", "配置", "启动", "调用链", "接口", "类", "文件", "sql", "redis", "rabbitmq")) {
            score++;
        }
        if (containsAny(normalized, "先", "再", "然后", "同时", "并且", "分别", "逐步", "一步一步", "最后")) {
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

    private boolean canManuallyOverride(String roleCode) {
        return "ADMIN".equals(roleCode) || "DEVELOPER".equals(roleCode);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "simplified";
        }
        return "opar".equalsIgnoreCase(mode.trim()) ? "opar" : "simplified";
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
                                  String reason) {
    }
}
