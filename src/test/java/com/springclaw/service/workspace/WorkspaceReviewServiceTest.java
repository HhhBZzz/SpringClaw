package com.springclaw.service.workspace;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class WorkspaceReviewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReviewWorkspaceWithoutLeakingSecretValues() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/demo/service"));
        Files.createDirectories(tempDir.resolve("src/test/java/com/demo/service"));
        Files.createDirectories(tempDir.resolve("frontend/src"));
        Files.createDirectories(tempDir.resolve("skills/workspace-review"));
        Files.createDirectories(tempDir.resolve("target/classes"));

        Files.writeString(tempDir.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        Files.writeString(tempDir.resolve("src/main/java/com/demo/service/UserService.java"), """
                package com.demo.service;

                public class UserService {
                    public String login(String username) {
                        // TODO add audit log
                        return username;
                    }
                }
                """);
        Files.writeString(tempDir.resolve("src/test/java/com/demo/service/UserServiceTest.java"), """
                package com.demo.service;

                class UserServiceTest {
                }
                """);
        Files.writeString(tempDir.resolve("frontend/package.json"), "{\"dependencies\":{\"vue\":\"latest\",\"vite\":\"latest\"}}");
        Files.writeString(tempDir.resolve("skills/workspace-review/SKILL.md"), "---\nname: Workspace Review\n---\n");
        Files.writeString(tempDir.resolve(".env.local"), "OPENCLAW_DEEPSEEK_API_KEY=sk-secret-value\nMYSQL_PASSWORD=plain-password\n");
        Files.writeString(tempDir.resolve("target/classes/App.class"), "generated");

        WorkspaceReviewService service = new WorkspaceReviewService(
                tempDir.toString(),
                8,
                300,
                20,
                512
        );

        String result = service.reviewWorkspace("审查项目架构是否合理");

        Assertions.assertTrue(result.contains("LOCAL_WORKSPACE_REVIEW"));
        Assertions.assertTrue(result.contains("Spring Boot"));
        Assertions.assertTrue(result.contains("Vue/Vite"));
        Assertions.assertTrue(result.contains("Skill 体系"));
        Assertions.assertTrue(result.contains(".env.local"));
        Assertions.assertTrue(result.contains("敏感配置"));
        Assertions.assertTrue(result.contains("已跳过生成/依赖目录"));
        Assertions.assertTrue(result.contains("TODO"));
        Assertions.assertFalse(result.contains("sk-secret-value"));
        Assertions.assertFalse(result.contains("plain-password"));
    }
}
