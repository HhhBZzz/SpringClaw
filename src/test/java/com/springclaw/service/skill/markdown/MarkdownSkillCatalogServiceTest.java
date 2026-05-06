package com.springclaw.service.skill.markdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarkdownSkillCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldImportMarkdownSkillFromUrlAndExposeItInRegistry() throws Exception {
        String skillMarkdown = """
                ---
                name: group-summary
                description: Summarize what happened in a group conversation.
                version: 1.0.0
                ---

                # Group Summary

                When asked to summarize a group conversation, produce:
                1. Key decisions
                2. Open questions
                3. Next actions
                """;
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(skillMarkdown);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        MarkdownSkillCatalogService service = new MarkdownSkillCatalogService(
                true,
                tempDir.toString(),
                new SkillCatalogService(true, tempDir.toString()),
                httpClient
        );

        MarkdownSkillCatalogService.ImportedSkillImportResult result = service.importFromUrl(
                new MarkdownSkillCatalogService.ImportMarkdownSkillRequest(
                        "https://example.test/skills/group-summary/SKILL.md",
                        null,
                        null,
                        null,
                        "总结群聊,今日总结",
                        "workspace,file",
                        "simplified",
                        "group-shared",
                        true,
                        55
                )
        );

        Assertions.assertEquals("group-summary", result.slug());
        Assertions.assertTrue(result.skillPath().endsWith("SKILL.md"));
        Assertions.assertTrue(result.definition().instructions().contains("Key decisions"));

        SkillDefinition definition = service.matchDefinition("总结群聊今天聊了什么", Set.of("workspace", "file"))
                .orElseThrow();
        Assertions.assertEquals("group-summary", definition.skillId());
        Assertions.assertEquals("MARKDOWN", definition.sourceType());
        Assertions.assertEquals("group-shared", definition.contextPolicy());
    }

    @Test
    void shouldNormalizeGitHubBlobUrlToRawUrl() throws IOException {
        MarkdownSkillCatalogService service = new MarkdownSkillCatalogService(
                true,
                tempDir.toString(),
                new ObjectMapper()
        );

        var request = new MarkdownSkillCatalogService.ImportMarkdownSkillRequest(
                "https://github.com/springclaw/example/blob/main/skills/group-summary/SKILL.md",
                "manual-skill",
                "Manual Skill",
                "manual",
                "manual",
                "workspace",
                "simplified",
                "session-only",
                true,
                60
        );

        String normalized = invokeNormalize(service, request.url());
        Assertions.assertEquals(
                "https://raw.githubusercontent.com/springclaw/example/main/skills/group-summary/SKILL.md",
                normalized
        );
    }

    @Test
    void shouldExtractReadmeFromClawhubLikeHtml() {
        MarkdownSkillCatalogService service = new MarkdownSkillCatalogService(
                true,
                tempDir.toString(),
                new ObjectMapper()
        );
        String html = """
                <html><body><script>
                window.__DATA__={readme:"---\\nname: summarize\\ndescription: Summarize a conversation.\\n---\\n\\n# Summarize\\n\\nUse this skill to summarize a conversation."}
                </script></body></html>
                """;
        String extracted = invokeReadmeExtract(service, html);
        Assertions.assertNotNull(extracted);
        Assertions.assertTrue(extracted.contains("name: summarize"));
        Assertions.assertTrue(extracted.contains("# Summarize"));
    }

    private String invokeNormalize(MarkdownSkillCatalogService service, String url) {
        try {
            var method = MarkdownSkillCatalogService.class.getDeclaredMethod("normalizeSourceUrl", String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, url);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private String invokeReadmeExtract(MarkdownSkillCatalogService service, String html) {
        try {
            var method = MarkdownSkillCatalogService.class.getDeclaredMethod("tryExtractClawhubReadme", String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, html);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
