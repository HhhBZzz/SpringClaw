package com.springclaw.service.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.markdown.MarkdownSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
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
        writeBuiltinSkill(
                "code-analysis",
                "代码分析",
                "分析项目结构",
                "workspace,file,script",
                "opar",
                "分析代码,定位代码",
                "用代码分析分析 ChatServiceImpl"
        );
        writeBuiltinSkill(
                "log-diagnostics",
                "日志诊断",
                "分析日志和报错",
                "script,workspace,file",
                "opar",
                "分析日志,分析报错",
                "分析这个报错"
        );
        Path skillDir = tempDir.resolve("repo_inspector");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.py"), "print('ok')\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: 项目分析技能
                description: 扫描当前工作区
                metadata:
                  springclaw:
                    springclaw:
                      skillId: repo_inspector
                      executor:
                        type: python
                        entrypoint: scripts/run.py
                      category: workspace
                      tier: core
                      inputHint: 传入 goal
                      priority: 10
                      agentVisible: true
                      toolPacks:
                        - script
                        - workspace
                      triggerKeywords:
                        - 项目分析
                      triggerExamples:
                        - 分析项目结构
                ---

                # Repo Inspector
                """);

        SkillRegistryService registryService = new SkillRegistryService(
                new SkillCatalogService(true, tempDir.toString())
        );

        List<SkillDefinition> definitions = registryService.listAllDefinitions();

        Assertions.assertTrue(definitions.stream().anyMatch(def -> "code-analysis".equals(def.skillId())));
        Assertions.assertTrue(definitions.stream().anyMatch(def -> "log-diagnostics".equals(def.skillId())));
        Assertions.assertTrue(definitions.stream().anyMatch(def -> "repo_inspector".equals(def.skillId())));
        Assertions.assertTrue(registryService.listAgentVisibleDefinitions(Set.of("script", "workspace")).size() >= 3);
    }

    @Test
    void shouldUseHighConfidenceMetadataWithoutBuiltinHardcoding() throws Exception {
        writeBuiltinSkill(
                "metadata-web",
                "Metadata Web",
                "metadata driven web skill",
                "script",
                "simplified",
                "网页",
                "读取网页"
        );
        Path skillPath = tempDir.resolve("metadata-web").resolve("SKILL.md");
        String markdown = Files.readString(skillPath);
        markdown = markdown.replace("triggerExamples:",
                "highConfidenceRequiresUrl: true\nhighConfidenceKeywords:\n  - 抓取\n  - 读取\ntriggerExamples:");
        Files.writeString(skillPath, markdown);
        SkillRegistryService registryService = new SkillRegistryService(new SkillCatalogService(true, tempDir.toString()));

        var matched = registryService.matchHighConfidenceDefinition("请读取 https://example.com 的正文");

        Assertions.assertTrue(matched.isPresent());
        Assertions.assertEquals("metadata-web", matched.orElseThrow().skillId());
    }

    private void writeBuiltinSkill(String skillId,
                                   String name,
                                   String description,
                                   String toolPacksCsv,
                                   String preferredMode,
                                   String triggerKeywordsCsv,
                                   String triggerExample) throws Exception {
        Path dir = tempDir.resolve(skillId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                metadata:
                  springclaw:
                    springclaw:
                      skillId: %s
                      executor:
                        type: builtin
                      category: builtin
                      tier: core
                      inputHint: 根据问题直接执行内建能力
                      priority: 10
                      agentVisible: true
                      toolPacks:
                %s
                      preferredMode: %s
                      triggerKeywords:
                %s
                      triggerExamples:
                        - %s
                ---

                # %s
                """.formatted(
                name,
                description,
                skillId,
                indentList(toolPacksCsv),
                preferredMode,
                indentList(triggerKeywordsCsv),
                triggerExample,
                name
        ));
    }

    private String indentList(String csv) {
        StringBuilder builder = new StringBuilder();
        for (String token : csv.split(",")) {
            String value = token.trim();
            if (!value.isEmpty()) {
                builder.append("        - ").append(value).append("\n");
            }
        }
        return builder.toString();
    }
}
