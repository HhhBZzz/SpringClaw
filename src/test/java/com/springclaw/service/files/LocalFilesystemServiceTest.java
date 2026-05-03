package com.springclaw.service.files;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class LocalFilesystemServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSearchAndReadOnlyAuthorizedRoots() throws Exception {
        Path documents = tempDir.resolve("Documents");
        Path projects = tempDir.resolve("Projects");
        Path outside = tempDir.resolve("Outside");
        Files.createDirectories(documents);
        Files.createDirectories(projects.resolve(".ssh"));
        Files.createDirectories(outside);

        Files.writeString(documents.resolve("resume.txt"), "Han resume with Spring AI experience");
        Files.writeString(projects.resolve("notes.md"), "SpringClaw local agent notes");
        Files.writeString(projects.resolve(".ssh/id_rsa"), "PRIVATE KEY");
        Files.writeString(outside.resolve("secret.txt"), "outside secret");

        LocalFilesystemService service = new LocalFilesystemService(
                documents + "," + projects,
                ".ssh,.gnupg,Library/Keychains,.env",
                12000,
                8,
                100,
                20,
                512
        );

        String roots = service.listAuthorizedRoots();
        Assertions.assertTrue(roots.contains("root1"));
        Assertions.assertTrue(roots.contains("Documents"));
        Assertions.assertTrue(roots.contains("Projects"));

        String search = service.searchFiles("resume");
        Assertions.assertTrue(search.contains("root1:resume.txt"));

        String content = service.readTextFile("root1", "resume.txt");
        Assertions.assertTrue(content.contains("Spring AI experience"));

        String grep = service.grepText("SpringClaw");
        Assertions.assertTrue(grep.contains("root2:notes.md"));

        Assertions.assertThrows(BusinessException.class, () -> service.readTextFile("root2", ".ssh/id_rsa"));
        Assertions.assertThrows(BusinessException.class, () -> service.readTextFile("root1", "../Outside/secret.txt"));
    }
}
