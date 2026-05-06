package com.springclaw.service.skill.bundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class SkillScaffoldServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreatePythonSkillTemplateInsideSkillRoot() throws Exception {
        SkillCatalogService catalogService = new SkillCatalogService(true, tempDir.toString());
        SkillScaffoldService scaffoldService = new SkillScaffoldService(true, catalogService);

        String result = scaffoldService.createPythonSkillTemplate(
                "demo_skill",
                "Demo Skill",
                "演示自定义 skill",
                "demo"
        );

        Assertions.assertTrue(result.contains("demo_skill"));
        Assertions.assertTrue(Files.isRegularFile(tempDir.resolve("demo_skill/SKILL.md")));
        Assertions.assertTrue(Files.isRegularFile(tempDir.resolve("demo_skill/scripts/run.py")));
        Assertions.assertEquals(1, catalogService.listBundles().size());
    }
}
