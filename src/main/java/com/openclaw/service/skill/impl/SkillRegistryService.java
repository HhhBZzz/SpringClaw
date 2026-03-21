package com.openclaw.service.skill.impl;

import com.openclaw.service.skill.SkillDefinition;
import com.openclaw.service.skill.markdown.MarkdownSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 skill 注册中心。
 */
@Service
public class SkillRegistryService {

    private final BuiltinSkillCatalogService builtinSkillCatalogService;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final MarkdownSkillCatalogService markdownSkillCatalogService;

    public SkillRegistryService(BuiltinSkillCatalogService builtinSkillCatalogService,
                                ScriptSkillCatalogService scriptSkillCatalogService,
                                MarkdownSkillCatalogService markdownSkillCatalogService) {
        this.builtinSkillCatalogService = builtinSkillCatalogService;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.markdownSkillCatalogService = markdownSkillCatalogService;
    }

    public List<SkillDefinition> listAllDefinitions() {
        List<SkillDefinition> result = new ArrayList<>();
        result.addAll(builtinSkillCatalogService.listDefinitions());
        for (ScriptSkillDefinition definition : scriptSkillCatalogService.listDefinitions()) {
            result.add(toScriptSkill(definition));
        }
        result.addAll(markdownSkillCatalogService.listDefinitions());
        return result.stream()
                .sorted(Comparator.comparingInt(SkillDefinition::priority)
                        .thenComparing(def -> def.skillId().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public List<SkillDefinition> listAgentVisibleDefinitions(Set<String> allowedToolPacks) {
        return listAllDefinitions().stream()
                .filter(SkillDefinition::enabled)
                .filter(SkillDefinition::agentVisible)
                .filter(definition -> definition.matchesAllowedToolPacks(allowedToolPacks))
                .toList();
    }

    public List<SkillDefinition> listCoreDefinitions(Set<String> allowedToolPacks) {
        return listAgentVisibleDefinitions(allowedToolPacks).stream()
                .filter(definition -> definition.priority() <= 30)
                .toList();
    }

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

    public Optional<SkillDefinition> matchBestAgentVisibleDefinition(String question, Set<String> allowedToolPacks) {
        return matchAgentVisibleDefinitions(question, allowedToolPacks, 1).stream().findFirst();
    }

    private SkillDefinition toScriptSkill(ScriptSkillDefinition definition) {
        return new SkillDefinition(
                definition.skillName(),
                definition.displayName(),
                definition.description(),
                "SCRIPT",
                definition.scriptPath().toString(),
                StringUtils.hasText(definition.inputHint()) ? definition.inputHint() : "传入自然语言 goal 执行脚本技能。",
                definition.keywords(),
                definition.exampleQuestions(),
                List.of("script"),
                "simplified",
                "session-only",
                "script",
                definition.skillName(),
                true,
                definition.priority(),
                definition.visibleToAgent()
        );
    }

    private int score(SkillDefinition definition, String normalizedQuestion) {
        int score = 0;
        if (definition.triggerKeywords() != null) {
            for (String keyword : definition.triggerKeywords()) {
                if (StringUtils.hasText(keyword) && normalizedQuestion.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                    score += 3;
                }
            }
        }
        for (String token : deriveNameTokens(definition)) {
            if (normalizedQuestion.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> deriveNameTokens(SkillDefinition definition) {
        List<String> tokens = new ArrayList<>();
        addNameTokens(tokens, definition.skillId());
        addNameTokens(tokens, definition.name());
        return tokens;
    }

    private void addNameTokens(List<String> target, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() >= 2) {
            target.add(normalized);
        }
        for (String token : normalized.split("[\\s_\\-/]+")) {
            if (token.length() >= 3) {
                target.add(token);
            }
        }
    }
}
