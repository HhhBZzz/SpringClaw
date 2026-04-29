package com.springclaw.service.skill.markdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.springclaw.service.skill.SkillDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

class MarkdownSkillCatalogServiceTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldImportMarkdownSkillFromUrlAndExposeItInRegistry() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
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
        server.createContext("/skills/group-summary/SKILL.md", exchange -> {
            byte[] body = skillMarkdown.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/markdown; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        MarkdownSkillCatalogService service = new MarkdownSkillCatalogService(
                true,
                tempDir.toString(),
                new ObjectMapper()
        );

        MarkdownSkillCatalogService.ImportedSkillImportResult result = service.importFromUrl(
                new MarkdownSkillCatalogService.ImportMarkdownSkillRequest(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/skills/group-summary/SKILL.md",
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
