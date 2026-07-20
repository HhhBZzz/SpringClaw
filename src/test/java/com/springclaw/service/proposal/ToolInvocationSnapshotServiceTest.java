package com.springclaw.service.proposal;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.workspace.GitOperations;
import com.springclaw.service.workspace.PathNormalizer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvocationSnapshotServiceTest {

    private static Path tmpRoot() throws Exception {
        Path p = Files.createTempDirectory("ws-snap-test");
        p.toFile().deleteOnExit();
        return p.toAbsolutePath().normalize();
    }

    private GitOperations mockGit(Path root, String head, List<String> dirty) {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(root);
        Mockito.when(git.headSha()).thenReturn(head);
        Mockito.when(git.statusNameOnly()).thenReturn(dirty);
        return git;
    }

    private ToolInvocationSnapshotService newService(GitOperations git) {
        return new ToolInvocationSnapshotService(new PathNormalizer(), git);
    }

    @Test
    void canonicalizeProducesSameHashForSemanticEqualArgs() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of());
        ToolInvocationSnapshotService svc = newService(git);

        String h1 = svc.argsHash("WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                new Object[]{"src/A.java", "hello"});
        String h2 = svc.argsHash("WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                new Object[]{"src/A.java", "hello"});
        String h3 = svc.argsHash("WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                new Object[]{"src/A.java", "world"});

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void captureWriteFile_dirtyClean_returnsSnapshot() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of());
        ToolInvocationSnapshotService svc = newService(git);

        ToolInvocationSnapshot snap = svc.capture(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                new Object[]{"src/A.java", "hello"},
                "write");

        assertThat(snap.targetPaths()).containsExactly("src/A.java");
        assertThat(snap.argumentsHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(snap.gitHeadShaAtCreate()).isEqualTo("abc1234");
        assertThat(snap.workspaceDirty()).isFalse();
        assertThat(snap.previewSummary()).contains("workspaceWriteFile").contains("src/A.java");
        assertThat(snap.argumentsCanonicalJson()).isNotBlank();
        assertThat(snap.riskLevel()).isEqualTo("write");
    }

    @Test
    void captureFileWriteTextFile_extractsTargetPath() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of());
        ToolInvocationSnapshotService svc = newService(git);

        ToolInvocationSnapshot snap = svc.capture(
                "FileToolPack.writeTextFile",
                "file",
                new Object[]{"notes/a.txt", "hello", true},
                "write");

        assertThat(snap.targetPaths()).containsExactly("notes/a.txt");
        assertThat(snap.previewSummary()).contains("writeTextFile").contains("notes/a.txt");
    }

    @Test
    void captureRejectsWhenTargetIntersectsDirty() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of("src/A.java"));
        ToolInvocationSnapshotService svc = newService(git);

        assertThatThrownBy(() -> svc.capture(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                new Object[]{"src/A.java", "hello"},
                "write"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40901))
                .hasMessageContaining("未提交改动");
    }

    @Test
    void captureWithDirtyButTargetClean_setsWorkspaceDirtyTrue() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of("docs/README.md"));
        ToolInvocationSnapshotService svc = newService(git);

        ToolInvocationSnapshot snap = svc.capture(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                new Object[]{"src/A.java", "hello"},
                "write");

        assertThat(snap.workspaceDirty()).isTrue();
        assertThat(snap.dirtyFilesAtCreate()).contains("docs/README.md");
        assertThat(snap.targetPaths()).containsExactly("src/A.java");
    }

    @Test
    void captureNormalizesPathFormDifference() throws Exception {
        Path root = tmpRoot();
        // dirty list uses bare form, target uses ./ prefix — both must normalize to same key
        GitOperations git = mockGit(root, "abc1234", List.of("src/A.java"));
        ToolInvocationSnapshotService svc = newService(git);

        assertThatThrownBy(() -> svc.capture(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                new Object[]{"./src/A.java", "hello"},
                "write"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40901));
    }

    @Test
    void captureRunCommand_emptyTargetPaths_acceptedEvenIfDirty() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of("src/A.java"));
        ToolInvocationSnapshotService svc = newService(git);

        ToolInvocationSnapshot snap = svc.capture(
                "WorkspaceEditToolPack.workspaceRunCommand",
                "workspace",
                new Object[]{"mvn test"},
                "dangerous");

        assertThat(snap.targetPaths()).isEmpty();
        assertThat(snap.previewSummary()).contains("mvn test");
        assertThat(snap.workspaceDirty()).isTrue();
        assertThat(snap.dirtyFilesAtCreate()).contains("src/A.java");
    }

    @Test
    void captureSystemRunCommand_previewIncludesCommand() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of());
        ToolInvocationSnapshotService svc = newService(git);

        ToolInvocationSnapshot snap = svc.capture(
                "SystemToolPack.runCommand",
                "system",
                new Object[]{"echo springclaw-approval-e2e"},
                "execution");

        assertThat(snap.previewSummary()).isEqualTo("runCommand: echo springclaw-approval-e2e");
    }

    @Test
    void captureRejectsCanonicalizeFailure() throws Exception {
        Path root = tmpRoot();
        GitOperations git = mockGit(root, "abc1234", List.of());
        ToolInvocationSnapshotService svc = newService(git);

        // self-referencing map → Jackson fails serializing
        Map<String, Object> selfRef = new HashMap<>();
        selfRef.put("self", selfRef);

        assertThatThrownBy(() -> svc.capture(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                new Object[]{"src/A.java", selfRef},
                "write"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(50090));
    }
}
