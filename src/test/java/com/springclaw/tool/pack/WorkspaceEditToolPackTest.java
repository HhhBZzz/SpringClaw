package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class WorkspaceEditToolPackTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path outsideDir;

    @Test
    void shouldRejectWritePathOutsideWorkspace() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceWriteFile("../outside.txt", "blocked"));

        Assertions.assertEquals(40067, ex.getCode());
    }

    @Test
    void shouldRejectDangerousCommand() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceRunCommand("rm -rf target"));

        Assertions.assertEquals(40065, ex.getCode());
    }

    @Test
    void shouldRunSafeCommandInsideWorkspace() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        String result = toolPack.workspaceRunCommand("printf ok");

        Assertions.assertTrue(result.contains("exitCode=0"));
        Assertions.assertTrue(result.contains("ok"));
    }

    @Test
    void shouldRejectCommandReadingParentPath() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceRunCommand("cat ../secret.txt"));

        Assertions.assertEquals(40065, ex.getCode());
    }

    @Test
    void shouldRejectCommandWritingParentPath() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceRunCommand("printf changed > ../secret.txt"));

        Assertions.assertEquals(40065, ex.getCode());
    }

    @Test
    void shouldRejectCommandChangingDirectoryToParent() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceRunCommand("cd .. && pwd"));

        Assertions.assertEquals(40065, ex.getCode());
    }

    @Test
    void shouldAllowRangeSyntaxThatIsNotParentPathTraversal() {
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        String result = toolPack.workspaceRunCommand("printf 'main..HEAD'");

        Assertions.assertTrue(result.contains("exitCode=0"));
        Assertions.assertTrue(result.contains("main..HEAD"));
    }

    @Test
    void shouldRejectWriteThroughSymlinkEscapingWorkspace() throws Exception {
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "outside secret");
        try {
            Files.createSymbolicLink(tempDir.resolve("linked-secret.txt"), outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.abort("symlink creation is not available in this environment: " + ex.getMessage());
        }
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceWriteFile("linked-secret.txt", "changed"));

        Assertions.assertEquals(40067, ex.getCode());
        Assertions.assertEquals("outside secret", Files.readString(outsideFile));
    }

    @Test
    void shouldRejectPatchThroughSymlinkEscapingWorkspace() throws Exception {
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "outside secret");
        try {
            Files.createSymbolicLink(tempDir.resolve("linked-secret.txt"), outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.abort("symlink creation is not available in this environment: " + ex.getMessage());
        }
        WorkspaceEditToolPack toolPack = new WorkspaceEditToolPack(tempDir.toString(), 5, 1000);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> toolPack.workspaceApplyPatch("linked-secret.txt", "outside", "changed"));

        Assertions.assertEquals(40067, ex.getCode());
        Assertions.assertEquals("outside secret", Files.readString(outsideFile));
    }
}
