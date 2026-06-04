package com.springclaw.service.skill.impl;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试 SkillRegistryService 对 builtin 类型 skill 的加载和匹配能力。
 * （原 BuiltinSkillCatalogService 已合并到 SkillRegistryService）
 */
class BuiltinSkillCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadBuiltinDefinitionsFromSkillDirectories() throws Exception {
        writeBuiltinSkill(
                "code-analysis",
                "代码分析",
                "分析项目结构",
                "workspace,file,script",
                "opar",
                "分析代码,定位代码",
                "用代码分析分析 ChatServiceImpl"
        );

        SkillRegistryService service = new SkillRegistryService(
                new SkillCatalogService(true, tempDir.toString()));

        List<SkillDefinition> definitions = service.listAllDefinitions();

        assertThat(definitions).hasSize(1);
        SkillDefinition definition = definitions.get(0);
        assertThat(definition.skillId()).isEqualTo("code-analysis");
        assertThat(definition.sourceType()).isEqualTo("BUILTIN");
        assertThat(definition.executorType()).isEqualTo("builtin");
        assertThat(definition.toolPacks()).containsExactly("workspace", "file", "script");
        assertThat(definition.preferredMode()).isEqualTo("opar");
    }

    @Test
    void shouldKeepHighConfidenceMatchingForWebCrawl() throws Exception {
        writeBuiltinSkill(
                "web-crawl",
                "网页抓取",
                "抓取网页正文",
                "script",
                "simplified",
                "抓取网页,读取网页,网页正文",
                "读取这个网页 https://example.com"
        );

        SkillRegistryService service = new SkillRegistryService(
                new SkillCatalogService(true, tempDir.toString()));

        assertThat(service.matchHighConfidenceDefinition("读取这个网页 https://example.com"))
                .map(SkillDefinition::skillId)
                .contains("web-crawl");
    }

    @Test
    void shouldTreatProjectStructureQuestionAsHighConfidenceCodeAnalysis() throws Exception {
        writeBuiltinSkill(
                "code-analysis",
                "代码分析",
                "分析项目结构",
                "workspace,file,script",
                "opar",
                "分析代码,定位代码,分析项目",
                "帮我看看项目结构"
        );

        SkillRegistryService service = new SkillRegistryService(
                new SkillCatalogService(true, tempDir.toString()));

        assertThat(service.matchHighConfidenceDefinition("帮我看看这个项目里结构是怎样的"))
                .map(SkillDefinition::skillId)
                .contains("code-analysis");
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
                skillId: %s
                type: builtin
                category: builtin
                tier: core
                inputHint: 根据问题直接执行内建能力
                priority: 10
                enabled: true
                agentVisible: true
                toolPacks:
                %s
                preferredMode: %s
                triggerKeywords:
                %s
                %s
                triggerExamples:
                  - %s
                ---

                # %s

                内建技能说明。
                """.formatted(
                name,
                description,
                skillId,
                indentList(toolPacksCsv),
                preferredMode,
                indentList(triggerKeywordsCsv),
                highConfidenceBlock(skillId),
                triggerExample,
                name
        ));
    }

    private String highConfidenceBlock(String skillId) {
        return switch (skillId) {
            case "code-analysis" -> """
                highConfidenceKeywords:
                  - 项目结构
                  - 结构
                  - 是否存在
                  - 文件
                """;
            case "web-crawl" -> """
                highConfidenceRequiresUrl: true
                highConfidenceKeywords:
                  - 抓取
                  - 读取
                  - 网页
                  - 链接
                  - 正文
                """;
            default -> "";
        };
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
