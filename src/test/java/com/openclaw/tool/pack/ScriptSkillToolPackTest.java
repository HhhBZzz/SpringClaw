package com.openclaw.tool.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.common.exception.BusinessException;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ScriptSkillToolPackTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnDisabledMessageWhenScriptToolDisabled() {
        ScriptSkillToolPack toolPack = new ScriptSkillToolPack(
                false,
                new ScriptSkillCatalogService(false, tempDir.toString(), "echo", new ObjectMapper()),
                "python3",
                5,
                1000,
                new ObjectMapper()
        );
        String result = toolPack.listScriptSkills();
        Assertions.assertTrue(result.contains("未开启"));
    }

    @Test
    void shouldRejectInvalidSkillName() {
        ScriptSkillToolPack toolPack = new ScriptSkillToolPack(
                true,
                new ScriptSkillCatalogService(true, tempDir.toString(), "echo", new ObjectMapper()),
                "python3",
                5,
                1000,
                new ObjectMapper()
        );
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.runScriptSkill("../bad", "{}"));
        Assertions.assertEquals(40097, ex.getCode());
    }

    @Test
    void shouldRunScriptSkillByGoal() throws Exception {
        Files.writeString(tempDir.resolve("echo.py"), """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print(payload.get("goal", ""))
                """);
        Files.writeString(tempDir.resolve("echo.skill.json"), """
                {
                  "displayName": "回声",
                  "category": "debug",
                  "description": "测试技能",
                  "inputHint": "goal",
                  "keywords": ["回声", "测试链路"],
                  "exampleQuestions": ["测试"]
                }
                """);
        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, tempDir.toString(), "echo", new ObjectMapper());
        ScriptSkillToolPack toolPack = new ScriptSkillToolPack(
                true,
                catalogService,
                "python3",
                5,
                1000,
                new ObjectMapper()
        );

        String result = toolPack.runScriptSkillByGoal("echo", "检查 skill goal");

        Assertions.assertTrue(result.contains("检查 skill goal"));
    }
}
