package com.springclaw.service.files;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
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

    @Test
    void shouldRejectSymlinkThatResolvesOutsideAuthorizedRoot() throws Exception {
        Path documents = tempDir.resolve("Documents");
        Path outside = tempDir.resolve("Outside");
        Files.createDirectories(documents);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "outside secret");
        try {
            Files.createSymbolicLink(documents.resolve("linked-secret.txt"), outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ex) {
            Assumptions.abort("symlink creation is not available in this environment: " + ex.getMessage());
        }

        LocalFilesystemService service = new LocalFilesystemService(
                documents.toString(),
                ".ssh,.gnupg,.env",
                12000,
                8,
                100,
                20,
                512
        );

        Assertions.assertThrows(BusinessException.class, () -> service.readTextFile("root1", "linked-secret.txt"));
        Assertions.assertFalse(service.searchFiles("linked-secret").contains("linked-secret.txt"));
    }

    @Test
    void shouldHideSensitiveCredentialFilesFromSearch() throws Exception {
        Path documents = tempDir.resolve("Documents");
        Files.createDirectories(documents);
        Files.writeString(documents.resolve("notes.txt"), "normal content");
        Files.writeString(documents.resolve("private.pem"), "PRIVATE KEY");

        LocalFilesystemService service = new LocalFilesystemService(
                documents.toString(),
                ".ssh,.gnupg,.env",
                12000,
                8,
                100,
                20,
                512
        );

        String search = service.searchFiles("private");

        Assertions.assertFalse(search.contains("private.pem"));
    }

    @Test
    void shouldHideBrowserProfilePathsAcrossPlatforms() throws Exception {
        Path documents = tempDir.resolve("Documents");
        Path windowsChromeProfile = documents.resolve("AppData/Local/Google/Chrome/User Data/Profile 1");
        Path macChromeProfile = documents.resolve("Library/Application Support/Google/Chrome/Profile 1");
        Path linuxChromeProfile = documents.resolve(".config/google-chrome/Profile 1");
        Files.createDirectories(windowsChromeProfile);
        Files.createDirectories(macChromeProfile);
        Files.createDirectories(linuxChromeProfile);
        Files.writeString(windowsChromeProfile.resolve("windows-cookies.txt"), "browser cookie data");
        Files.writeString(macChromeProfile.resolve("mac-cookies.txt"), "browser cookie data");
        Files.writeString(linuxChromeProfile.resolve("linux-cookies.txt"), "browser cookie data");
        Files.writeString(documents.resolve("chrome-notes.txt"), "safe project note");

        LocalFilesystemService service = new LocalFilesystemService(
                documents.toString(),
                ".ssh,.gnupg,.env",
                12000,
                12,
                100,
                20,
                512
        );

        String search = service.searchFiles("cookies");

        Assertions.assertFalse(search.contains("windows-cookies.txt"));
        Assertions.assertFalse(search.contains("mac-cookies.txt"));
        Assertions.assertFalse(search.contains("linux-cookies.txt"));
        Assertions.assertThrows(BusinessException.class,
                () -> service.readTextFile("root1", "AppData/Local/Google/Chrome/User Data/Profile 1/windows-cookies.txt"));
        Assertions.assertThrows(BusinessException.class,
                () -> service.readTextFile("root1", "Library/Application Support/Google/Chrome/Profile 1/mac-cookies.txt"));
        Assertions.assertThrows(BusinessException.class,
                () -> service.readTextFile("root1", ".config/google-chrome/Profile 1/linux-cookies.txt"));
    }
}
