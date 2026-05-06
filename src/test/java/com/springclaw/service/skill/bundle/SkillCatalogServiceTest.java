package com.springclaw.service.skill.bundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SkillCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExposeNeutralSkillCatalogName() {
        SkillCatalogService service = new SkillCatalogService(true, tempDir.toString());

        Assertions.assertTrue(service.enabled());
        Assertions.assertTrue(service.rootPath().endsWith(tempDir));
    }

    @Test
    void shouldLoadBuiltinScriptAndPromptSkillsFromSingleRoot() throws Exception {
        writeSkill(
                "code-analysis",
                "代码分析",
                "分析代码结构",
                "builtin",
                null
        );
        writeSkill(
                "repo_inspector",
                "项目分析技能",
                "扫描工作区",
                "python",
                "scripts/run.py"
        );
        writeSkill(
                "clawhub-summarize",
                "Summarize",
                "总结内容",
                "prompt",
                null
        );

        SkillCatalogService service = new SkillCatalogService(true, tempDir.toString());

        List<SkillBundleDefinition> bundles = service.listBundles();

        Assertions.assertEquals(3, bundles.size());
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "code-analysis".equals(bundle.skillId()) && "builtin".equals(bundle.executorType())));
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "repo_inspector".equals(bundle.skillId()) && "python".equals(bundle.executorType())));
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "clawhub-summarize".equals(bundle.skillId()) && "prompt".equals(bundle.executorType())));
    }

    @Test
    void shouldInferOpenClawStyleSkillWithoutFrontmatter() throws Exception {
        Path skillDir = tempDir.resolve("weather_probe");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/weather.py"), "print('weather')\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                # Weather Probe

                ## Description
                查询城市天气，并返回温度对比。

                ## Usage
                传入 city 或 goal。
                """);

        SkillCatalogService service = new SkillCatalogService(true, tempDir.toString());

        SkillBundleDefinition bundle = service.listBundles().stream()
                .filter(candidate -> "weather_probe".equals(candidate.skillId()))
                .findFirst()
                .orElseThrow();

        Assertions.assertEquals("Weather Probe", bundle.name());
        Assertions.assertEquals("查询城市天气，并返回温度对比。", bundle.description());
        Assertions.assertEquals("python", bundle.executorType());
        Assertions.assertTrue(bundle.entrypointPath().endsWith("scripts/weather.py"));
    }

    @Test
    void shouldLoadSkillsFromExternalRoots() throws Exception {
        Path externalRoot = tempDir.resolve("external-skills");
        Files.createDirectories(externalRoot);
        writeSkill(
                "local_skill",
                "本地技能",
                "主目录技能",
                "prompt",
                null
        );
        writeSkill(
                externalRoot,
                "external_skill",
                "外部技能",
                "外部目录技能",
                "prompt",
                null
        );

        SkillCatalogService service = new SkillCatalogService(
                true,
                tempDir.toString(),
                externalRoot.toString()
        );

        List<SkillBundleDefinition> bundles = service.listBundles();

        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "local_skill".equals(bundle.skillId())));
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "external_skill".equals(bundle.skillId())));
    }

    private void writeSkill(String skillId,
                            String name,
                            String description,
                            String executorType,
                            String entrypoint) throws Exception {
        Path dir = tempDir.resolve(skillId);
        Files.createDirectories(dir);
        if (entrypoint != null) {
            Path entrypointPath = dir.resolve(entrypoint);
            Files.createDirectories(entrypointPath.getParent());
            Files.writeString(entrypointPath, "print('ok')\n");
        }
        String entrypointBlock = entrypoint == null ? "" : "entrypoint: %s\n".formatted(entrypoint);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                skillId: %s
                type: %s
                %s---

                # %s
                """.formatted(name, description, skillId, executorType, entrypointBlock, name));
    }

    private void writeSkill(Path root,
                            String skillId,
                            String name,
                            String description,
                            String executorType,
                            String entrypoint) throws Exception {
        Path dir = root.resolve(skillId);
        Files.createDirectories(dir);
        if (entrypoint != null) {
            Path entrypointPath = dir.resolve(entrypoint);
            Files.createDirectories(entrypointPath.getParent());
            Files.writeString(entrypointPath, "print('ok')\n");
        }
        String entrypointBlock = entrypoint == null ? "" : "entrypoint: %s\n".formatted(entrypoint);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                skillId: %s
                type: %s
                %s---

                # %s
                """.formatted(name, description, skillId, executorType, entrypointBlock, name));
    }
}
