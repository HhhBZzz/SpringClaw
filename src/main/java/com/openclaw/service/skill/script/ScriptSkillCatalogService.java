package com.openclaw.service.skill.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 脚本技能目录服务。
 *
 * 负责扫描 skills 目录中的脚本与元数据，给工具层和 Prompt 层复用。
 */
@Service
public class ScriptSkillCatalogService {

    private final boolean enabled;
    private final Path rootPath;
    private final Set<String> allowedSkills;
    private final ObjectMapper objectMapper;

    public ScriptSkillCatalogService(@Value("${openclaw.tools.script.enabled:false}") boolean enabled,
                                     @Value("${openclaw.tools.script.root:${user.dir}/skills}") String root,
                                     @Value("${openclaw.tools.script.allowed-skills:*}") String allowedSkills,
                                     ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.allowedSkills = Arrays.stream(allowedSkills.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return enabled;
    }

    public Path rootPath() {
        return rootPath;
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
        String normalizedGoal = normalizeText(goal);
        if (!StringUtils.hasText(normalizedGoal)) {
            return Optional.empty();
        }
        return listPublicDefinitions().stream()
                .map(definition -> Map.entry(definition, scoreDefinition(definition, normalizedGoal)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private List<ScriptSkillDefinition> scanDefinitions() {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return List.of();
        }
        try (var stream = Files.list(rootPath)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".py"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(this::toDefinition)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .sorted(Comparator.comparingInt(ScriptSkillDefinition::priority)
                            .thenComparing(def -> def.skillName().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
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

    private Optional<ScriptSkillDefinition> toDefinition(Path scriptPath) {
        String fileName = scriptPath.getFileName().toString();
        String skillName = fileName.substring(0, fileName.length() - 3);
        if (!isAllowed(skillName)) {
            return Optional.empty();
        }
        SkillMetadata metadata = readMetadata(skillName);
        String displayName = StringUtils.hasText(metadata.displayName()) ? metadata.displayName().trim() : skillName;
        String category = StringUtils.hasText(metadata.category()) ? metadata.category().trim() : "general";
        String tier = StringUtils.hasText(metadata.tier()) ? metadata.tier().trim() : "utility";
        String description = StringUtils.hasText(metadata.description())
                ? metadata.description().trim()
                : "执行本地脚本技能";
        String inputHint = StringUtils.hasText(metadata.inputHint())
                ? metadata.inputHint().trim()
                : "传入一个 goal，描述你要它做什么";
        List<String> keywords = metadata.keywords() == null
                ? Collections.emptyList()
                : metadata.keywords().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
        List<String> examples = metadata.exampleQuestions() == null
                ? Collections.emptyList()
                : metadata.exampleQuestions().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(3)
                .toList();
        int priority = metadata.priority() == null ? 100 : Math.max(0, metadata.priority());
        boolean visibleToAgent = metadata.visibleToAgent() == null || metadata.visibleToAgent();
        return Optional.of(new ScriptSkillDefinition(
                skillName,
                displayName,
                category,
                tier,
                description,
                inputHint,
                keywords,
                examples,
                priority,
                visibleToAgent,
                scriptPath
        ));
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

    private SkillMetadata readMetadata(String skillName) {
        Path metadataPath = rootPath.resolve(skillName + ".skill.json");
        if (!Files.isRegularFile(metadataPath)) {
            return SkillMetadata.empty();
        }
        try {
            return objectMapper.readValue(metadataPath.toFile(), SkillMetadata.class);
        } catch (IOException ex) {
            return SkillMetadata.empty();
        }
    }

    private boolean isAllowed(String skillName) {
        if (allowedSkills.isEmpty()) {
            return false;
        }
        if (allowedSkills.contains("*")) {
            return true;
        }
        return allowedSkills.contains(skillName);
    }

    private int scoreDefinition(ScriptSkillDefinition definition, String normalizedGoal) {
        int score = 0;
        score += scoreField(normalizedGoal, definition.skillName(), 120);
        score += scoreField(normalizedGoal, definition.displayName(), 100);
        score += scoreField(normalizedGoal, definition.category(), 60);
        for (String keyword : definition.keywords()) {
            score += scoreField(normalizedGoal, keyword, 40);
        }
        for (String exampleQuestion : definition.exampleQuestions()) {
            score += scoreField(normalizedGoal, exampleQuestion, 15);
        }
        return score;
    }

    private int scoreField(String normalizedGoal, String rawField, int weight) {
        String normalizedField = normalizeText(rawField);
        if (!StringUtils.hasText(normalizedGoal) || !StringUtils.hasText(normalizedField)) {
            return 0;
        }
        return normalizedGoal.contains(normalizedField) ? weight : 0;
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("　", "")
                .replace("-", "")
                .replace("_", "");
    }

    public record SkillMetadata(String displayName,
                                String category,
                                String tier,
                                String description,
                                String inputHint,
                                List<String> keywords,
                                List<String> exampleQuestions,
                                Integer priority,
                                Boolean visibleToAgent) {

        static SkillMetadata empty() {
            return new SkillMetadata("", "", "", "", "", List.of(), List.of(), 100, true);
        }
    }
}
