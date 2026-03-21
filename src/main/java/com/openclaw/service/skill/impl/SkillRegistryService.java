package com.openclaw.service.skill.impl;

import com.openclaw.service.skill.SkillDefinition;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统一 skill 注册中心。
 */
@Service
public class SkillRegistryService {

    private final BuiltinSkillCatalogService builtinSkillCatalogService;
    private final ScriptSkillCatalogService scriptSkillCatalogService;

    public SkillRegistryService(BuiltinSkillCatalogService builtinSkillCatalogService,
                                ScriptSkillCatalogService scriptSkillCatalogService) {
        this.builtinSkillCatalogService = builtinSkillCatalogService;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
    }

    public List<SkillDefinition> listAllDefinitions() {
        List<SkillDefinition> result = new ArrayList<>();
        result.addAll(builtinSkillCatalogService.listDefinitions());
        for (ScriptSkillDefinition definition : scriptSkillCatalogService.listDefinitions()) {
            result.add(toScriptSkill(definition));
        }
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
}
