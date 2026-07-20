package com.springclaw.service.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceIdentityTest {

    @TempDir
    Path tempDir;

    private final WorkspaceIdentity identity = new WorkspaceIdentity();

    @Test
    void idIsStableForEquivalentCanonicalPaths() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path equivalent = workspace.resolve("nested").resolve("..");
        Files.createDirectories(workspace.resolve("nested"));

        assertThat(identity.id(equivalent)).isEqualTo(identity.id(workspace));
        assertThat(identity.id(workspace)).matches("[0-9a-f]{64}");
    }

    @Test
    void idDiffersForDifferentPhysicalWorkspaces() throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("first"));
        Path second = Files.createDirectories(tempDir.resolve("second"));

        assertThat(identity.id(first)).isNotEqualTo(identity.id(second));
    }
}
