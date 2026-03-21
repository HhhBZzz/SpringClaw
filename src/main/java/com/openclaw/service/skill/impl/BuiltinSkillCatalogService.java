package com.openclaw.service.skill.impl;

import com.openclaw.service.skill.SkillDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 内置 skill 目录。
 */
@Service
public class BuiltinSkillCatalogService {

    private final List<SkillDefinition> definitions = List.of(
            new SkillDefinition(
                    "code-analysis",
                    "代码分析",
                    "分析项目结构、类、文件和实现位置，适合定位功能实现与阅读代码。",
                    "BUILTIN",
                    "openclaw:builtin:code-analysis",
                    "优先使用 workspace/file/script 能力分析项目实现位置与代码结构；若已有明显证据，直接给出定位结论。",
                    List.of("代码分析", "分析代码", "分析项目", "找实现", "定位代码", "看看类", "看看文件", "用代码分析"),
                    List.of("用代码分析分析 ChatServiceImpl", "帮我找这个功能在哪实现"),
                    List.of("workspace", "file", "script"),
                    "opar",
                    "session-only",
                    "builtin",
                    "code-analysis",
                    true,
                    10,
                    true
            ),
            new SkillDefinition(
                    "log-diagnostics",
                    "日志诊断",
                    "分析日志、报错、堆栈和启动失败现象，适合排查运行时问题。",
                    "BUILTIN",
                    "openclaw:builtin:log-diagnostics",
                    "优先解释最近错误与日志证据，必要时调用 runtime/workspace/script 能力辅助判断，不要空泛总结。",
                    List.of("日志诊断", "分析日志", "分析报错", "看看报错", "堆栈分析", "启动失败", "端口占用", "排查异常"),
                    List.of("分析这个报错", "帮我看看这段日志怎么回事"),
                    List.of("script", "workspace", "file"),
                    "opar",
                    "session-only",
                    "builtin",
                    "log-diagnostics",
                    true,
                    20,
                    true
            )
    );

    public List<SkillDefinition> listDefinitions() {
        return definitions;
    }

    public Optional<SkillDefinition> matchDefinition(String question) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return definitions.stream()
                .map(definition -> java.util.Map.entry(definition, score(definition, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<java.util.Map.Entry<SkillDefinition, Integer>>comparingInt(java.util.Map.Entry::getValue).reversed())
                .map(java.util.Map.Entry::getKey)
                .findFirst();
    }

    private int score(SkillDefinition definition, String normalizedQuestion) {
        int score = 0;
        for (String keyword : definition.triggerKeywords()) {
            if (normalizedQuestion.contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 2;
            }
        }
        if ("code-analysis".equals(definition.skillId())) {
            if (containsAny(normalizedQuestion, "代码", "类", "方法", "接口", "实现", "文件", "项目")) {
                score += 2;
            }
            if (containsAny(normalizedQuestion, "分析", "定位", "看看", "找", "梳理")) {
                score += 1;
            }
        }
        if ("log-diagnostics".equals(definition.skillId())) {
            if (containsAny(normalizedQuestion, "日志", "报错", "错误", "异常", "堆栈", "超时", "启动失败", "端口")) {
                score += 2;
            }
            if (containsAny(normalizedQuestion, "分析", "诊断", "排查", "看看", "怎么回事")) {
                score += 1;
            }
        }
        return score;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
