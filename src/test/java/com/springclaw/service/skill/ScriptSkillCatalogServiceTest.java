package com.springclaw.service.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
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
        writePythonSkill(tempDir, "repo_inspector", "项目分析技能", "workspace", "core", "用于定位实现文件",
                "goal", 10, true,
                "项目分析", "找实现",
                "分析 ChatServiceImpl");

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

        writePythonSkill(tempDir, "runtime_probe", "运行诊断技能", "runtime", "core", "看端口与进程",
                "goal", 20, true,
                "端口占用", "运行诊断",
                "看 18080 端口");

        Assertions.assertEquals(1, service.reloadDefinitions().size());
        Assertions.assertEquals("runtime_probe", service.matchBestDefinition("请用运行诊断技能看看 18080 端口").orElseThrow().skillName());
    }

    @Test
    void shouldHideInternalDebugSkillFromPromptAndMatching() throws Exception {
        writePythonSkill(tempDir, "echo", "回声技能", "debug", "debug", "调试链路",
                "goal", 999, false,
                "回声", "echo",
                "运行 echo");

        ScriptSkillCatalogService service = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());

        Assertions.assertEquals(1, service.listDefinitions().size());
        Assertions.assertTrue(service.listPublicDefinitions().isEmpty());
        Assertions.assertFalse(service.describeForPrompt().contains("回声技能"));
        Assertions.assertTrue(service.matchBestDefinition("运行 echo").isEmpty());
        Assertions.assertTrue(service.findDefinition("echo").isPresent());
    }

    private void writePythonSkill(Path root,
                                  String skillId,
                                  String name,
                                  String category,
                                  String tier,
                                  String description,
                                  String inputHint,
                                  int priority,
                                  boolean agentVisible,
                                  String keyword1,
                                  String keyword2,
                                  String example) throws Exception {
        Path skillDir = root.resolve(skillId);
        Path scriptsDir = skillDir.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("run.py"), "print('ok')\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                skillId: %s
                type: python
                entrypoint: scripts/run.py
                category: %s
                tier: %s
                inputHint: %s
                priority: %d
                enabled: true
                agentVisible: %s
                triggerKeywords:
                  - %s
                  - %s
                triggerExamples:
                  - %s
                ---

                # %s
                """.formatted(name, description, skillId, category, tier, inputHint, priority, agentVisible, keyword1, keyword2, example, name));
    }
}
