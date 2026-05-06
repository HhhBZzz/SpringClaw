package com.springclaw.tool.pack;

import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.bundle.SkillUsageService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class SkillLibraryToolPackTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldListAndViewSkillLikeHermes() throws Exception {
        Path skillDir = tempDir.resolve("demo_skill");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("references/guide.md"), "guide content\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: Demo Skill
                description: 用来验证 Hermes 风格 skill_view
                skillId: demo_skill
                type: prompt
                category: demo
                ---

                # Demo Skill

                ## Procedure
                先查看，再执行。
                """);
        SkillLibraryToolPack toolPack = new SkillLibraryToolPack(
                true,
                new SkillCatalogService(true, tempDir.toString())
        );

        String list = toolPack.skillsList();
        String view = toolPack.skillView("demo_skill");
        String file = toolPack.skillViewFile("demo_skill", "references/guide.md");

        Assertions.assertTrue(list.contains("demo_skill"));
        Assertions.assertTrue(view.contains("SKILL.md"));
        Assertions.assertTrue(view.contains("先查看，再执行"));
        Assertions.assertTrue(view.contains("references/guide.md"));
        Assertions.assertTrue(file.contains("guide content"));
    }

    @Test
    void shouldRejectUnsafeSupportingFilePath() throws Exception {
        Path skillDir = tempDir.resolve("demo_skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: Demo Skill
                description: demo
                skillId: demo_skill
                type: prompt
                ---

                # Demo Skill
                """);
        SkillLibraryToolPack toolPack = new SkillLibraryToolPack(
                true,
                new SkillCatalogService(true, tempDir.toString())
        );

        String result = toolPack.skillViewFile("demo_skill", "../secret.txt");

        Assertions.assertTrue(result.contains("不允许读取"));
    }

    @Test
    void shouldTrackSkillViewAndRunUsageInSidecar() throws Exception {
        Path skillDir = tempDir.resolve("demo_skill");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.py"), """
                import json
                import sys
                payload = json.loads(sys.argv[1])
                print("ran=" + payload.get("goal", ""))
                """);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: Demo Skill
                description: demo
                skillId: demo_skill
                type: python
                entrypoint: scripts/run.py
                category: demo
                ---

                # Demo Skill
                """);
        ObjectMapper objectMapper = new ObjectMapper();
        SkillCatalogService catalogService = new SkillCatalogService(true, tempDir.toString());
        SkillUsageService usageService = new SkillUsageService(true, catalogService, objectMapper);
        SkillLibraryToolPack toolPack = new SkillLibraryToolPack(true, catalogService, usageService);
        ScriptSkillCatalogService scriptCatalogService =
                new ScriptSkillCatalogService(true, tempDir.toString(), "*", objectMapper);
        ScriptSkillExecutorService executorService =
                new ScriptSkillExecutorService(true, scriptCatalogService, "python3", 5, 2000, objectMapper, usageService);

        String view = toolPack.skillView("demo_skill");
        String run = executorService.runScriptSkillByGoal("demo_skill", "hello");
        String status = toolPack.skillsStatus();

        Assertions.assertTrue(view.contains("Demo Skill"));
        Assertions.assertTrue(run.contains("ran=hello"));
        Assertions.assertTrue(Files.exists(tempDir.resolve(".usage.json")));
        Assertions.assertTrue(status.contains("demo_skill"));
        Assertions.assertTrue(status.contains("viewCount=1"));
        Assertions.assertTrue(status.contains("useCount=1"));
    }
}
