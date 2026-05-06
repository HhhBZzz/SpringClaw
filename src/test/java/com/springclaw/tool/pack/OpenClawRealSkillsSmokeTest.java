package com.springclaw.tool.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
                "crypto_price"
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
}
