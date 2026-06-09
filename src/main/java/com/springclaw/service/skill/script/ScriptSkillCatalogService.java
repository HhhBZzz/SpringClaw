package com.springclaw.service.skill.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillBundleDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.bundle.SkillBundleSupport;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Python script skill 目录服务。
 */
@Service
public class ScriptSkillCatalogService {

    private final boolean enabled;
    private final SkillCatalogService skillCatalogService;
    private final Set<String> allowedSkills;
    private final SkillRegistryService skillRegistryService;

    public ScriptSkillCatalogService(boolean enabled,
                                     String root,
                                     String allowedSkills,
                                     ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.skillCatalogService = new SkillCatalogService(enabled, root);
        this.allowedSkills = parseAllowedSkills(allowedSkills);
        this.skillRegistryService = null;
    }

    @Autowired
    public ScriptSkillCatalogService(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                                     @Value("${springclaw.skills.allowed:*}") String allowedSkills,
                                     SkillCatalogService skillCatalogService,
                                     SkillRegistryService skillRegistryService) {
        this.enabled = enabled;
        this.skillCatalogService = skillCatalogService;
        this.allowedSkills = parseAllowedSkills(allowedSkills);
        this.skillRegistryService = skillRegistryService;
    }

    public boolean enabled() {
        return enabled;
    }

    public Path rootPath() {
        return skillCatalogService.rootPath();
    }

    public List<ScriptSkillDefinition> listDefinitions() {
        return scanDefinitions();
    }

    public List<ScriptSkillDefinition> listPublicDefinitions() {
        return listDefinitions().stream()
                .filter(ScriptSkillDefinition::visibleToAgent)
                .toList();
    }

    public List<ScriptSkillDefinition> listCoreDefinitions() {
        return listPublicDefinitions().stream()
                .filter(definition -> "core".equals(normalizeText(definition.tier())))
                .toList();
    }

    public List<ScriptSkillDefinition> reloadDefinitions() {
        return scanDefinitions();
    }

    public List<ScriptSkillDefinition> findByCategory(String category) {
        String normalizedCategory = normalizeText(category);
        if (!StringUtils.hasText(normalizedCategory)) {
            return List.of();
        }
        return listDefinitions().stream()
                .filter(definition -> normalizeText(definition.category()).equals(normalizedCategory))
                .toList();
    }

    public Optional<ScriptSkillDefinition> matchBestDefinition(String goal) {
        return matchBestDefinition(goal, 1);
    }

    public Optional<ScriptSkillDefinition> matchBestDefinition(String goal, int minScore) {
        String normalizedGoal = normalizeText(goal);
        if (!StringUtils.hasText(normalizedGoal)) {
            return Optional.empty();
        }
        if (skillRegistryService != null) {
            // 统一打分：委托 SkillRegistryService 评分，再映射回 ScriptSkillDefinition
            List<ScriptSkillDefinition> scriptDefs = listPublicDefinitions();
            Set<String> scriptIds = scriptDefs.stream()
                    .map(ScriptSkillDefinition::skillName)
                    .collect(Collectors.toSet());
            return skillRegistryService.listAllDefinitions().stream()
                    .filter(SkillDefinition::enabled)
                    .filter(def -> "python".equalsIgnoreCase(def.executorType()) || "script".equalsIgnoreCase(def.executorType()))
                    .filter(def -> scriptIds.contains(def.skillId()))
                    .map(def -> Map.entry(def, skillRegistryService.score(def, normalizedGoal)))
                    .filter(entry -> entry.getValue() >= minScore)
                    .max(Map.Entry.comparingByValue())
                    .flatMap(entry -> findDefinition(entry.getKey().skillId()));
        }
        // 无 registry 时退回本地打分（测试兼容）
        return listPublicDefinitions().stream()
                .map(definition -> Map.entry(definition, scoreDefinitionLocal(definition, normalizedGoal)))
                .filter(entry -> entry.getValue() >= minScore)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    public Optional<ScriptSkillDefinition> findDefinition(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            return Optional.empty();
        }
        String normalized = normalizeText(skillName);
        return listDefinitions().stream()
                .filter(def -> normalizeText(def.skillName()).equals(normalized)
                        || normalizeText(def.displayName()).equals(normalized))
                .findFirst();
    }

    public String describeForPrompt() {
        List<ScriptSkillDefinition> definitions = listPublicDefinitions();
        if (definitions.isEmpty()) {
            return "（暂无脚本技能）";
        }
        return describeDefinitionsForPrompt(definitions);
    }

    public String describeForTool() {
        List<ScriptSkillDefinition> definitions = listPublicDefinitions();
        if (definitions.isEmpty()) {
            return "暂无可用脚本技能";
        }
        List<String> lines = new ArrayList<>();
        for (ScriptSkillDefinition definition : definitions) {
            String examples = definition.exampleQuestions().isEmpty()
                    ? ""
                    : "\n  示例问题: " + String.join(" | ", definition.exampleQuestions());
            String keywords = definition.keywords().isEmpty()
                    ? ""
                    : "\n  关键词: " + String.join(" | ", definition.keywords());
            lines.add("- skill=" + definition.skillName()
                    + "\n  名称: " + definition.displayName()
                    + "\n  分类: " + definition.category()
                    + "\n  描述: " + definition.description()
                    + "\n  输入方式: " + definition.inputHint()
                    + keywords
                    + examples);
        }
        return String.join("\n", lines);
    }

    public String describeCoreForPrompt() {
        List<ScriptSkillDefinition> definitions = listCoreDefinitions();
        if (definitions.isEmpty()) {
            return "（暂无核心脚本技能）";
        }
        return describeDefinitionsForPrompt(definitions);
    }

    private List<ScriptSkillDefinition> scanDefinitions() {
        if (!enabled) {
            return List.of();
        }
        return skillCatalogService.listBundles().stream()
                .filter(this::isScriptBundle)
                .filter(bundle -> isAllowed(bundle.skillId()))
                .map(this::toDefinition)
                .sorted(Comparator.comparingInt(ScriptSkillDefinition::priority)
                        .thenComparing(def -> def.skillName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private boolean isScriptBundle(SkillBundleDefinition definition) {
        return "python".equalsIgnoreCase(definition.executorType())
                && definition.entrypointPath() != null;
    }

    private ScriptSkillDefinition toDefinition(SkillBundleDefinition definition) {
        return new ScriptSkillDefinition(
                definition.skillId(),
                definition.name(),
                definition.category(),
                definition.tier(),
                definition.description(),
                definition.inputHint(),
                definition.triggerKeywords(),
                definition.triggerExamples(),
                definition.priority(),
                definition.agentVisible(),
                definition.bundlePath(),
                definition.entrypointPath()
        );
    }

    private String describeDefinitionsForPrompt(List<ScriptSkillDefinition> definitions) {
        List<String> core = new ArrayList<>();
        List<String> utility = new ArrayList<>();
        for (ScriptSkillDefinition definition : definitions) {
            String examples = definition.exampleQuestions().isEmpty()
                    ? ""
                    : "；示例：" + String.join(" / ", definition.exampleQuestions());
            String keywords = definition.keywords().isEmpty()
                    ? ""
                    : "；关键词：" + String.join("、", definition.keywords());
            String line = "- " + definition.displayName()
                    + " (" + definition.skillName() + ", category=" + definition.category() + ", tier=" + definition.tier() + "): "
                    + definition.description() + "；输入：" + definition.inputHint() + keywords + examples;
            if ("core".equals(normalizeText(definition.tier()))) {
                core.add(line);
            } else {
                utility.add(line);
            }
        }
        List<String> sections = new ArrayList<>();
        if (!core.isEmpty()) {
            sections.add("核心脚本技能:\n" + String.join("\n", core));
        }
        if (!utility.isEmpty()) {
            sections.add("实用脚本技能:\n" + String.join("\n", utility));
        }
        return String.join("\n", sections);
    }

    private Set<String> parseAllowedSkills(String allowedSkills) {
        return Arrays.stream(allowedSkills.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isAllowed(String skillName) {
        if (allowedSkills.isEmpty()) {
            return false;
        }
        if (allowedSkills.contains("*")) {
            return true;
        }
        String normalized = normalizeText(skillName);
        return allowedSkills.stream().map(this::normalizeText).anyMatch(normalized::equals);
    }

    private int scoreDefinitionLocal(ScriptSkillDefinition definition, String normalizedGoal) {
        int score = 0;
        for (String keyword : definition.keywords()) {
            String normalizedKeyword = normalizeText(keyword);
            if (StringUtils.hasText(normalizedKeyword) && normalizedGoal.contains(normalizedKeyword)) {
                score += 3;
            }
        }
        if (normalizedGoal.contains(normalizeText(definition.skillName()))) {
            score += 4;
        }
        if (normalizedGoal.contains(normalizeText(definition.displayName()))) {
            score += 3;
        }
        if (normalizedGoal.contains(normalizeText(definition.category()))) {
            score += 1;
        }
        return score;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
