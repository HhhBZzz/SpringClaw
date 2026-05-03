package com.springclaw.service.prompt;

import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.skill.SkillService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

class SoulPromptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDescribeAuthorizedLocalFileBoundaryInSystemPrompt() throws Exception {
        Path authorizedRoot = tempDir.resolve("Documents");
        Files.createDirectories(authorizedRoot);

        SoulPromptService service = new SoulPromptService(
                new StubSkillService(),
                new LocalFilesystemService(
                        authorizedRoot.toString(),
                        ".ssh,.gnupg,.env",
                        12000,
                        8,
                        100,
                        20,
                        512
                )
        );

        String prompt = service.buildSystemPrompt("api", "u1");

        Assertions.assertTrue(prompt.contains("本地文件访问边界"));
        Assertions.assertTrue(prompt.contains("当前不是只能读取项目目录"));
        Assertions.assertTrue(prompt.contains("root1"));
        Assertions.assertTrue(prompt.contains("Documents"));
    }

    private static class StubSkillService implements SkillService {

        @Override
        public Set<String> resolveAllowedToolPacks(String channel, String userId) {
            return Set.of("file", "workspace");
        }

        @Override
        public String describeAvailableSkills(String channel, String userId) {
            return "- 本地授权文件 (local-files): 浏览授权目录";
        }

        @Override
        public String describeCoreSkills(String channel, String userId) {
            return "- Local Files：浏览授权目录";
        }
    }
}
