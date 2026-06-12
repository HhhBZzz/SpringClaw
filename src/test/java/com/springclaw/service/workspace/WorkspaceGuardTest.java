package com.springclaw.service.workspace;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class WorkspaceGuardTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path outsideDir;

    @Test
    void shouldAllowPathInsideWorkspace() {
        WorkspaceGuard guard = new WorkspaceGuard(tempDir.toString());

        WorkspaceGuard.Decision decision = guard.checkPath("src/main/App.java");

        Assertions.assertEquals(WorkspaceGuard.Action.ALLOW, decision.action());
        Assertions.assertEquals("ALLOW", decision.reasonCode());
        Assertions.assertTrue(decision.resolvedPath().startsWith(tempDir.toAbsolutePath().normalize()));
    }

    @Test
    void shouldRejectPathTraversalOutsideWorkspace() {
        WorkspaceGuard guard = new WorkspaceGuard(tempDir.toString());

        WorkspaceGuard.Decision decision = guard.checkPath("../secret.txt");

        Assertions.assertEquals(WorkspaceGuard.Action.REJECT, decision.action());
        Assertions.assertEquals("PATH_OUTSIDE_WORKSPACE", decision.reasonCode());
    }

    @Test
    void shouldRejectSymlinkResolvedOutsideWorkspace() throws Exception {
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "outside secret");
        try {
            Files.createSymbolicLink(tempDir.resolve("linked-secret.txt"), outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.abort("symlink creation is not available in this environment: " + ex.getMessage());
        }
        WorkspaceGuard guard = new WorkspaceGuard(tempDir.toString());

        WorkspaceGuard.Decision decision = guard.checkPath("linked-secret.txt");

        Assertions.assertEquals(WorkspaceGuard.Action.REJECT, decision.action());
        Assertions.assertEquals("PATH_OUTSIDE_WORKSPACE", decision.reasonCode());
    }

    @Test
    void shouldRejectCommandWithParentPathSegment() {
        WorkspaceGuard guard = new WorkspaceGuard(tempDir.toString());

        WorkspaceGuard.Decision decision = guard.checkCommand("cat ../secret.txt");

        Assertions.assertEquals(WorkspaceGuard.Action.REJECT, decision.action());
        Assertions.assertEquals("COMMAND_PARENT_PATH", decision.reasonCode());
    }

    @Test
    void shouldThrowGuardExceptionWithDecisionWhenCommandRejected() {
        WorkspaceGuard guard = new WorkspaceGuard(tempDir.toString());

        WorkspaceGuard.WorkspaceGuardException ex = Assertions.assertThrows(
                WorkspaceGuard.WorkspaceGuardException.class,
                () -> guard.requireCommand("cat ../secret.txt"));

        Assertions.assertEquals(40065, ex.getCode());
        Assertions.assertEquals(WorkspaceGuard.Action.REJECT, ex.decision().action());
        Assertions.assertEquals("COMMAND_PARENT_PATH", ex.decision().reasonCode());
    }

    @Test
    void shouldAllowCommandWithRangeSyntax() {
        WorkspaceGuard guard = new WorkspaceGuard(tempDir.toString());

        WorkspaceGuard.Decision decision = guard.checkCommand("printf 'main..HEAD'");

        Assertions.assertEquals(WorkspaceGuard.Action.ALLOW, decision.action());
    }
}
