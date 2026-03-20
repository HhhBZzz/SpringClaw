package com.openclaw.service.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ScriptSkillCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadSkillMetadata() throws Exception {
        Files.writeString(tempDir.resolve("repo_inspector.py"), "print('ok')\n");
        Files.writeString(tempDir.resolve("repo_inspector.skill.json"), """
                {
                  "displayName": "项目分析技能",
                  "category": "workspace",
                  "tier": "core",
                  "description": "用于定位实现文件",
                  "inputHint": "goal",
                  "priority": 10,
                  "visibleToAgent": true,
                  "keywords": ["项目分析", "找实现"],
                  "exampleQuestions": ["分析 ChatServiceImpl"]
                }
                """);

        ScriptSkillCatalogService service = new ScriptSkillCatalogService(true, tempDir.toString(), "repo_inspector", new ObjectMapper());

        String description = service.describeForPrompt();
        String coreDescription = service.describeCoreForPrompt();

        Assertions.assertTrue(description.contains("项目分析技能"));
        Assertions.assertTrue(description.contains("核心脚本技能"));
        Assertions.assertTrue(description.contains("分析 ChatServiceImpl"));
        Assertions.assertTrue(coreDescription.contains("项目分析技能"));
        Assertions.assertTrue(service.findDefinition("repo_inspector").isPresent());
        Assertions.assertEquals("workspace", service.findDefinition("repo_inspector").orElseThrow().category());
        Assertions.assertEquals("core", service.findDefinition("repo_inspector").orElseThrow().tier());
        Assertions.assertEquals("repo_inspector", service.matchBestDefinition("请用项目分析技能帮我找实现").orElseThrow().skillName());
    }

    @Test
    void shouldReloadNewSkillWithoutRestart() throws Exception {
        ScriptSkillCatalogService service = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());

        Assertions.assertTrue(service.listDefinitions().isEmpty());

        Files.writeString(tempDir.resolve("runtime_probe.py"), "print('ok')\n");
        Files.writeString(tempDir.resolve("runtime_probe.skill.json"), """
                {
                  "displayName": "运行诊断技能",
                  "category": "runtime",
                  "tier": "core",
                  "description": "看端口与进程",
                  "inputHint": "goal",
                  "priority": 20,
                  "visibleToAgent": true,
                  "keywords": ["端口占用", "运行诊断"],
                  "exampleQuestions": ["看 18080 端口"]
                }
                """);

        Assertions.assertEquals(1, service.reloadDefinitions().size());
        Assertions.assertEquals("runtime_probe", service.matchBestDefinition("请用运行诊断技能看看 18080 端口").orElseThrow().skillName());
    }

    @Test
    void shouldHideInternalDebugSkillFromPromptAndMatching() throws Exception {
        Files.writeString(tempDir.resolve("echo.py"), "print('ok')\n");
        Files.writeString(tempDir.resolve("echo.skill.json"), """
                {
                  "displayName": "回声技能",
                  "category": "debug",
                  "tier": "debug",
                  "description": "调试链路",
                  "inputHint": "goal",
                  "priority": 999,
                  "visibleToAgent": false,
                  "keywords": ["回声", "echo"],
                  "exampleQuestions": ["运行 echo"]
                }
                """);

        ScriptSkillCatalogService service = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());

        Assertions.assertEquals(1, service.listDefinitions().size());
        Assertions.assertTrue(service.listPublicDefinitions().isEmpty());
        Assertions.assertFalse(service.describeForPrompt().contains("回声技能"));
        Assertions.assertTrue(service.matchBestDefinition("运行 echo").isEmpty());
        Assertions.assertTrue(service.findDefinition("echo").isPresent());
    }
}
