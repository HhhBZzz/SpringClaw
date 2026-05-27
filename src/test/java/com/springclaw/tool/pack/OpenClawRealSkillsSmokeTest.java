package com.springclaw.tool.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawRealSkillsSmokeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path skillsRoot = Path.of(System.getProperty("user.dir")).resolve("skills");

    @Test
    void shouldRegisterOpenClawInspiredExecutableSkills() {
        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, skillsRoot.toString(), "*", objectMapper);

        Set<String> skillIds = catalogService.listDefinitions().stream()
                .map(definition -> definition.skillName())
                .collect(Collectors.toSet());

        assertThat(skillIds).contains(
                "system_status",
                "content_summarizer",
                "rss_blog_watcher",
                "crypto_price",
                "pdf_generator",
                "ppt_generator",
                "video_frames",
                "office_file_tools"
        );
    }

    @Test
    void shouldRunOpenClawInspiredSkillsWithoutExternalAccounts() {
        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, skillsRoot.toString(), "*", objectMapper);
        ScriptSkillExecutorService executorService = new ScriptSkillExecutorService(
                true,
                catalogService,
                "python3",
                8,
                4000,
                objectMapper
        );

        String status = executorService.runScriptSkill("system_status", "{\"goal\":\"查看系统状态\"}");
        String summary = executorService.runScriptSkill("content_summarizer", "{\"text\":\"SpringClaw uses skills. Skills are reusable. SpringClaw can run Python skills.\"}");
        String rss = executorService.runScriptSkill("rss_blog_watcher", "{\"action\":\"list\"}");
        String crypto = executorService.runScriptSkill("crypto_price", "{\"symbols\":\"BTC,ETH\",\"dryRun\":true}");

        assertThat(status).doesNotContain("Traceback").doesNotContain("status=failed");
        assertThat(summary).doesNotContain("Traceback").doesNotContain("status=failed");
        assertThat(rss).doesNotContain("Traceback").doesNotContain("status=failed");
        assertThat(crypto).doesNotContain("Traceback").doesNotContain("status=failed");
        assertThat(status).contains("system_status");
        assertThat(summary).contains("content_summarizer").contains("summary");
        assertThat(rss).contains("rss_blog_watcher");
        assertThat(crypto).contains("crypto_price").contains("dryRun");
    }

    @Test
    void shouldRunOfficeLocalFileSkillsInDryRunMode() throws Exception {
        Path markdown = Path.of(System.getProperty("user.dir"))
                .resolve("target/test-data/office-file-tools/notes.md");
        Files.createDirectories(markdown.getParent());
        Files.writeString(markdown, """
                # 周报

                本周完成 skill runtime 收口。
                下周继续补充办公文件自动化能力。
                """);

        ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, skillsRoot.toString(), "*", objectMapper);
        ScriptSkillExecutorService executorService = new ScriptSkillExecutorService(
                true,
                catalogService,
                "python3",
                8,
                6000,
                objectMapper
        );

        String pdf = executorService.runScriptSkill("pdf_generator", objectMapper.writeValueAsString(Map.of(
                "dryRun", true,
                "title", "测试文档",
                "text", "Hello PDF"
        )));
        String ppt = executorService.runScriptSkill("ppt_generator", objectMapper.writeValueAsString(Map.of(
                "dryRun", true,
                "title", "测试演示",
                "text", "第一页\n第二页\n第三页"
        )));
        String video = executorService.runScriptSkill("video_frames", objectMapper.writeValueAsString(Map.of(
                "dryRun", true,
                "videoPath", "sample.mp4",
                "mode", "info"
        )));
        String office = executorService.runScriptSkill("office_file_tools", objectMapper.writeValueAsString(Map.of(
                "action", "summarize",
                "inputFile", markdown.toString(),
                "dryRun", true
        )));
        String longPdf = executorService.runScriptSkill("pdf_generator", objectMapper.writeValueAsString(Map.of(
                "dryRun", true,
                "filename", "a".repeat(150),
                "text", "Hello PDF"
        )));
        String longPpt = executorService.runScriptSkill("ppt_generator", objectMapper.writeValueAsString(Map.of(
                "dryRun", true,
                "filename", "b".repeat(150),
                "text", "第一页"
        )));

        assertThat(pdf).contains("pdf_generator").contains("dryRun");
        assertThat(ppt).contains("ppt_generator").contains("dryRun");
        assertThat(video).contains("video_frames").contains("dryRun");
        assertThat(office).contains("office_file_tools").contains("lineCount").contains("wordCount");
        assertThat(longPdf).contains(".pdf");
        assertThat(longPpt).contains(".pptx");
        assertThat(pdf + ppt + video + office + longPdf + longPpt).doesNotContain("Traceback").doesNotContain("status=failed");
    }
}
