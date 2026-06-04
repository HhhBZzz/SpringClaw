package com.springclaw.service.skill.impl;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillBundleDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 skill 注册中心。
 * 所有 skill 定义均来自 skills/ 下的 SKILL.md 文件，
 * 通过 SkillCatalogService 扫描和解析。
 */
@Service
public class SkillRegistryService {

    private final SkillCatalogService skillCatalogService;

    public SkillRegistryService(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    /** 列出所有 skill 定义，按 priority + skillId 排序 */
    public List<SkillDefinition> listAllDefinitions() {
        return skillCatalogService.listBundles().stream()
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

    /** 高置信度匹配：由 SKILL.md metadata 描述，不在 Java 注册中心写死 skillId。 */
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

    /** keyword 评分：触发词命中 +3，名称 token 命中 +1。 */
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
        return score;
    }

    /** 高置信度条件由 highConfidenceKeywords 和 highConfidenceRequiresUrl 共同决定。 */
    private boolean isHighConfidenceMatch(SkillDefinition definition, String normalizedQuestion) {
        List<String> keywords = definition.highConfidenceKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        if (definition.highConfidenceRequiresUrl() && !hasUrl(normalizedQuestion)) {
            return false;
        }
        return containsAnyKeyword(normalizedQuestion, keywords);
    }

    private boolean hasUrl(String text) {
        return text.contains("http://") || text.contains("https://") || text.contains("www.");
    }

    private boolean containsAnyKeyword(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
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
