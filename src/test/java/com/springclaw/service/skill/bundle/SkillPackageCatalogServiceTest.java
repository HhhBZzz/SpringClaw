package com.springclaw.service.skill.bundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SkillPackageCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadBuiltinScriptAndPromptPackagesFromSingleRoot() throws Exception {
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

        SkillPackageCatalogService service = new SkillPackageCatalogService(true, tempDir.toString());

        List<SkillBundleDefinition> bundles = service.listBundles();

        Assertions.assertEquals(3, bundles.size());
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "code-analysis".equals(bundle.skillId()) && "builtin".equals(bundle.executorType())));
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "repo_inspector".equals(bundle.skillId()) && "python".equals(bundle.executorType())));
        Assertions.assertTrue(bundles.stream().anyMatch(bundle -> "clawhub-summarize".equals(bundle.skillId()) && "prompt".equals(bundle.executorType())));
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
}
