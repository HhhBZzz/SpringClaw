package com.springclaw.service.workspace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitOperationsTest {

    @Test
    void statusNameOnly_expandsUntrackedDirectoriesToFiles() throws Exception {
        Path repo = Files.createTempDirectory("git-ops-test");
        run(repo, "git", "init");

        Path file = repo.resolve("Desktop/springclaw-demo-write.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "demo", StandardCharsets.UTF_8);

        GitOperations git = new GitOperations(repo.toString());

        List<String> status = git.statusNameOnly();

        assertThat(status).contains("Desktop/springclaw-demo-write.txt");
        assertThat(status).doesNotContain("Desktop/");
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(String.join(" ", command) + " failed: " + output);
        }
    }
}
