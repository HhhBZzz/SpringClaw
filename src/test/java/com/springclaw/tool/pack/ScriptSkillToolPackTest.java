package com.springclaw.tool.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        Path skillDir = tempDir.resolve("echo");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.py"), """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print(payload.get("goal", ""))
                """);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: 回声
                description: 测试技能
                skillId: echo
                type: python
                entrypoint: scripts/run.py
                category: debug
                inputHint: goal
                priority: 50
                enabled: true
                agentVisible: true
                triggerKeywords:
                  - 回声
                  - 测试链路
                triggerExamples:
                  - 测试
                ---

                # Echo
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

    @Test
    void shouldRunScriptSkillThroughRuntime() {
        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        ScriptSkillToolPack toolPack = new ScriptSkillToolPack(true, catalogService, runtimeService, null);
        when(runtimeService.executeBySkillId("echo", "hello", Set.of("script"))).thenReturn("runtime result");

        String result = toolPack.runScriptSkillByGoal("echo", "hello");

        Assertions.assertEquals("runtime result", result);
        verify(runtimeService).executeBySkillId("echo", "hello", Set.of("script"));
    }

    @Test
    void shouldInspectScriptSkillWithSupportingFiles() throws Exception {
        Path skillDir = tempDir.resolve("inspector");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("scripts/run.py"), "print('inspect')\n");
        Files.writeString(skillDir.resolve("references/guide.md"), "使用说明\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: 检查器
                description: 查看 skill 说明
                skillId: inspector
                type: python
                entrypoint: scripts/run.py
                ---

                # Inspector
                """);
        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, tempDir.toString(), "inspector", new ObjectMapper());
        ScriptSkillToolPack toolPack = new ScriptSkillToolPack(
                true,
                catalogService,
                "python3",
                5,
                1000,
                new ObjectMapper()
        );

        String result = toolPack.inspectScriptSkill("inspector");

        Assertions.assertTrue(result.contains("SKILL.md"));
        Assertions.assertTrue(result.contains("references/guide.md"));
        Assertions.assertTrue(result.contains("scripts/run.py"));
    }

    @Test
    void shouldRunScriptSkillChainWithPreviousResult() throws Exception {
        writePythonSkill("first", """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print("first:" + payload.get("goal", ""))
                """);
        writePythonSkill("second", """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print("second:" + payload.get("previousResult", ""))
                """);
        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
        ScriptSkillToolPack toolPack = new ScriptSkillToolPack(
                true,
                catalogService,
                "python3",
                5,
                2000,
                new ObjectMapper()
        );

        String result = toolPack.runScriptSkillChain("first -> second", "hello");

        Assertions.assertTrue(result.contains("skillChain=first -> second"));
        Assertions.assertTrue(result.contains("first:hello"));
        Assertions.assertTrue(result.contains("second:skill=first"));
    }

    private void writePythonSkill(String skillId, String script) throws Exception {
        Path skillDir = tempDir.resolve(skillId);
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.py"), script);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s skill
                skillId: %s
                type: python
                entrypoint: scripts/run.py
                ---

                # %s
                """.formatted(skillId, skillId, skillId, skillId));
    }
}
