package com.springclaw.service.skill.impl;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillBundleDefinition;
import com.springclaw.service.skill.bundle.SkillPackageCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 skill 注册中心。
 * 所有 skill 定义均来自 skills/packages/ 下的 SKILL.md 文件，
 * 通过 SkillPackageCatalogService 扫描和解析。
 */
@Service
public class SkillRegistryService {

    private final SkillPackageCatalogService packageCatalogService;

    public SkillRegistryService(SkillPackageCatalogService packageCatalogService) {
        this.packageCatalogService = packageCatalogService;
    }

    /** 列出所有 skill 定义，按 priority + skillId 排序 */
    public List<SkillDefinition> listAllDefinitions() {
        return packageCatalogService.listBundles().stream()
                .map(SkillBundleDefinition::toRuntimeDefinition)
                .sorted(Comparator.comparingInt(SkillDefinition::priority)
                        .thenComparing(def -> def.skillId().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** 列出所有已启用且对 Agent 可见的 skill */
    public List<SkillDefinition> listAgentVisibleDefinitions(Set<String> allowedToolPacks) {
        return listAllDefinitions().stream()
                .filter(SkillDefinition::enabled)
                .filter(SkillDefinition::agentVisible)
                .filter(definition -> definition.matchesAllowedToolPacks(allowedToolPacks))
                .toList();
    }

    /** 列出核心（priority <= 30）skill */
    public List<SkillDefinition> listCoreDefinitions(Set<String> allowedToolPacks) {
        return listAgentVisibleDefinitions(allowedToolPacks).stream()
                .filter(definition -> definition.priority() <= 30)
                .toList();
    }

    /** 按 keyword 匹配最相关的 N 个 skill */
    public List<SkillDefinition> matchAgentVisibleDefinitions(String question, Set<String> allowedToolPacks, int limit) {
        if (!StringUtils.hasText(question) || limit <= 0) {
            return List.of();
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return listAgentVisibleDefinitions(allowedToolPacks).stream()
                .map(definition -> java.util.Map.entry(definition, score(definition, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<java.util.Map.Entry<SkillDefinition, Integer>>comparingInt(java.util.Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().priority()))
                .limit(limit)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /** 匹配最佳 skill */
    public Optional<SkillDefinition> matchBestAgentVisibleDefinition(String question, Set<String> allowedToolPacks) {
        return matchAgentVisibleDefinitions(question, allowedToolPacks, 1).stream().findFirst();
    }

    /** 匹配单个 skill（不限制 toolPacks），用于内部调用方 */
    public Optional<SkillDefinition> matchDefinition(String question) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return listAllDefinitions().stream()
                .filter(SkillDefinition::enabled)
                .map(definition -> java.util.Map.entry(definition, score(definition, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey);
    }

    /**
     * 高置信度匹配：主要用于 builtin 类型中需要精确触发条件的场景。
     * 当前仅 web-crawl 有高置信度条件（需要同时包含 URL 和抓取相关关键词）。
     */
    public Optional<SkillDefinition> matchHighConfidenceDefinition(String question) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return listAllDefinitions().stream()
                .filter(SkillDefinition::enabled)
                .filter(SkillDefinition::agentVisible)
                .filter(definition -> isHighConfidenceMatch(definition, normalized))
                .findFirst();
    }

    /** keyword 评分：触发词命中 +3，名称 token 命中 +1，特定硬编码评分额外的内置逻辑 */
    private int score(SkillDefinition definition, String normalizedQuestion) {
        int score = 0;
        // 触发词匹配
        if (definition.triggerKeywords() != null) {
            for (String keyword : definition.triggerKeywords()) {
                if (StringUtils.hasText(keyword) && normalizedQuestion.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                    score += 3;
                }
            }
        }
        // 名称 token 匹配
        for (String token : deriveNameTokens(definition)) {
            if (normalizedQuestion.contains(token)) {
                score += 1;
            }
        }
        // 内置硬编码评分（保留 BuiltinSkillCatalogService 的特定匹配逻辑）
        score += hardcodedScore(definition.skillId(), normalizedQuestion);
        return score;
    }

    /** 特定 skill 的硬编码评分逻辑 */
    private int hardcodedScore(String skillId, String normalizedQuestion) {
        return switch (skillId) {
            case "code-analysis" -> {
                int s = 0;
                if (containsAny(normalizedQuestion, "代码", "类", "方法", "接口", "实现", "文件", "项目")) s += 2;
                if (containsAny(normalizedQuestion, "分析", "定位", "看看", "找", "梳理")) s += 1;
                yield s;
            }
            case "workspace-review" -> {
                int s = 0;
                if (containsAny(normalizedQuestion, "项目", "源码", "代码", "架构", "模块", "文件")) s += 2;
                if (containsAny(normalizedQuestion, "审查", "review", "检查", "评估", "优化", "冗余", "垃圾代码", "技术债", "风险")) s += 3;
                yield s;
            }
            case "log-diagnostics" -> {
                int s = 0;
                if (containsAny(normalizedQuestion, "日志", "报错", "错误", "异常", "堆栈", "超时", "启动失败", "端口")) s += 2;
                if (containsAny(normalizedQuestion, "分析", "诊断", "排查", "看看", "怎么回事")) s += 1;
                yield s;
            }
            case "web-crawl" -> {
                int s = 0;
                if (containsAny(normalizedQuestion, "网页", "页面", "链接", "网址", "url", "正文")) s += 2;
                if (containsAny(normalizedQuestion, "抓取", "爬取", "读取", "打开", "提取", "总结")) s += 1;
                if (normalizedQuestion.contains("http://") || normalizedQuestion.contains("https://") || normalizedQuestion.contains("www.")) s += 3;
                yield s;
            }
            default -> 0;
        };
    }

    /** 高置信度条件：需要同时满足 URL + 抓取意图 */
    private boolean isHighConfidenceMatch(SkillDefinition definition, String normalizedQuestion) {
        if ("code-analysis".equals(definition.skillId())) {
            return containsAny(normalizedQuestion, "项目结构", "工程结构", "目录结构", "代码结构", "项目架构")
                    || (containsAny(normalizedQuestion, "项目", "工程", "代码", "类", "方法", "接口", "实现", "文件", "模块", "目录")
                    && containsAny(normalizedQuestion, "分析", "定位", "看看", "找", "梳理", "结构", "在哪", "怎样"));
        }
        if ("workspace-review".equals(definition.skillId())) {
            return containsAny(normalizedQuestion, "审查项目", "项目审查", "审查源码", "源码审查", "架构审查", "代码审查")
                    || (containsAny(normalizedQuestion, "项目", "工程", "源码", "代码", "架构", "模块")
                    && containsAny(normalizedQuestion, "审查", "review", "检查", "评估", "优化", "冗余", "垃圾代码", "技术债", "风险"));
        }
        if ("web-crawl".equals(definition.skillId())) {
            return containsAny(normalizedQuestion, "http://", "https://", "www.")
                    && containsAny(normalizedQuestion,
                    "抓取", "爬取", "读取", "打开", "提取", "总结", "网页", "页面", "链接", "网址", "url", "正文");
        }
        return false;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private List<String> deriveNameTokens(SkillDefinition definition) {
        List<String> tokens = new java.util.ArrayList<>();
        addNameTokens(tokens, definition.skillId());
        addNameTokens(tokens, definition.name());
        return tokens;
    }

    private void addNameTokens(List<String> target, String raw) {
        if (!StringUtils.hasText(raw)) return;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() >= 2) target.add(normalized);
        for (String token : normalized.split("[\\s_\\-/]+")) {
            if (token.length() >= 3) target.add(token);
        }
    }
}
