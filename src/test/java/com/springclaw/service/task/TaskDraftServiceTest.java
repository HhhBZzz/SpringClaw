package com.springclaw.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.task.impl.TaskDraftServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaskDraftServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseWebCrawlerDraft() throws Exception {
        writeScriptSkill("web_crawler", "网页抓取", "web");
        ScriptSkillCatalogService scriptCatalog = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
        SkillRegistryService registryService = new SkillRegistryService(
                new SkillCatalogService(true, tempDir.toString())
        );
        TaskDraftService service = new TaskDraftServiceImpl(new TaskScheduleSupport(), scriptCatalog, registryService);

        TaskCreationDraft draft = service.parseDraft("tester", "feishu", "每天9点帮我抓取这个网页 https://example.com 并发到飞书");

        assertThat(draft.scheduleType()).isEqualTo("preset");
        assertThat(draft.scheduleExpression()).isEqualTo("DAILY@09:00");
        assertThat(draft.targetType()).isEqualTo("skill");
        assertThat(draft.targetRef()).isEqualTo("web_crawler");
        assertThat(draft.deliveryMode()).isEqualTo("FEISHU");
    }

    @Test
    void shouldFallbackToAgentPrompt() throws Exception {
        ScriptSkillCatalogService scriptCatalog = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
        SkillRegistryService registryService = new SkillRegistryService(
                new SkillCatalogService(true, tempDir.toString())
        );
        TaskDraftService service = new TaskDraftServiceImpl(new TaskScheduleSupport(), scriptCatalog, registryService);

        TaskCreationDraft draft = service.parseDraft("tester", "api", "每周一 10 点总结今天的 AI 新闻并给我一个简报");

        assertThat(draft.targetType()).isEqualTo("agent");
        assertThat(draft.inputPayload()).contains("总结今天的 AI 新闻");
    }

    private void writeScriptSkill(String skillId, String name, String category) throws Exception {
        Path skillDir = tempDir.resolve(skillId);
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.py"), "print('ok')\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: test
                skillId: %s
                type: python
                entrypoint: scripts/run.py
                category: %s
                tier: core
                inputHint: goal
                priority: 10
                enabled: true
                agentVisible: true
                triggerKeywords:
                  - 抓取网页
                ---
                # %s
                """.formatted(name, skillId, category, name));
    }
}
