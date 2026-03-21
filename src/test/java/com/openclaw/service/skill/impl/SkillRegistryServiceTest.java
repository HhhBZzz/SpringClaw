package com.openclaw.service.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.service.skill.SkillDefinition;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

class SkillRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExposeBuiltinAndScriptSkillsInUnifiedRegistry() throws Exception {
        Files.writeString(tempDir.resolve("repo_inspector.py"), "print('ok')\n");
        Files.writeString(tempDir.resolve("repo_inspector.skill.json"), """
                {
                  "displayName": "项目分析技能",
                  "category": "workspace",
                  "tier": "core",
                  "description": "扫描当前工作区",
                  "inputHint": "传入 goal",
                  "priority": 10,
                  "visibleToAgent": true,
                  "keywords": ["项目分析"],
                  "exampleQuestions": ["分析项目结构"]
                }
                """);

        SkillRegistryService registryService = new SkillRegistryService(
                new BuiltinSkillCatalogService(),
                new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper())
        );

        List<SkillDefinition> definitions = registryService.listAllDefinitions();

        Assertions.assertTrue(definitions.stream().anyMatch(def -> "code-analysis".equals(def.skillId())));
        Assertions.assertTrue(definitions.stream().anyMatch(def -> "log-diagnostics".equals(def.skillId())));
        Assertions.assertTrue(definitions.stream().anyMatch(def -> "repo_inspector".equals(def.skillId())));
        Assertions.assertTrue(registryService.listAgentVisibleDefinitions(Set.of("script", "workspace")).size() >= 3);
    }
}
