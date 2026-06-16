package com.springclaw.service.workspace;

import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceGitGuardTest {

    private Path tmpRoot;
    private PathNormalizer pathNormalizer;
    private ToolInvocationProposalRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("ws-guard-test");
        tmpRoot.toFile().deleteOnExit();
        pathNormalizer = new PathNormalizer();
        repository = Mockito.mock(ToolInvocationProposalRepository.class);
    }

    private ToolInvocationProposal proposal(String headSha, List<String> targetPaths) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                null, "tip-test-1", "req-1", "run-1",
                "session-A", "user-1", "USER",
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                "[]", "deadbeef",
                "write", targetPaths, "preview",
                false, List.of(),
                ToolInvocationProposalStatus.EXECUTING, 0,
                null, null, null,
                headSha, null, null, List.of(),
                null, null,
                now, now, now.plusMinutes(15)
        );
    }

    @SafeVarargs
    private GitOperations mockGit(Path tmpRoot, String headSha, List<String>... statusReturns) {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn(headSha);
        if (statusReturns.length == 1) {
            Mockito.when(git.statusNameOnly()).thenReturn(statusReturns[0]);
        } else if (statusReturns.length >= 2) {
            Mockito.when(git.statusNameOnly())
                    .thenReturn(statusReturns[0])
                    .thenReturn(statusReturns[1]);
        }
        return git;
    }

    @Test
    void happyPath_commitAndRecordSuccess() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234").thenReturn("def5678");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of())
                .thenReturn(List.of("src/A.java"));
        Mockito.when(git.commit(Mockito.anyString())).thenReturn("def5678");
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.when(repository.recordCommit(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyList(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        String result = guard.execute(p, () -> "ok");

        Assertions.assertEquals("ok", result);

        Mockito.verify(git).add(List.of("src/A.java"));
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(git).commit(msgCaptor.capture());
        Assertions.assertTrue(msgCaptor.getValue().contains("proposalId: tip-test-1"),
                "commit message should include proposalId");

        Mockito.verify(repository).recordBaseline("tip-test-1", "abc1234");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> changedCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(repository).recordCommit(
                Mockito.eq("tip-test-1"),
                Mockito.eq("def5678"),
                changedCaptor.capture(),
                Mockito.eq("ok"));
        Assertions.assertEquals(List.of("src/A.java"), changedCaptor.getValue());
    }

    @Test
    void baselineMismatch_throwsSecurityException() throws Exception {
        GitOperations git = mockGit(tmpRoot, "xyz0000");

        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        @SuppressWarnings("unchecked")
        Callable<String> tool = Mockito.mock(Callable.class);

        SecurityException ex = Assertions.assertThrows(SecurityException.class,
                () -> guard.execute(p, tool));
        Assertions.assertTrue(ex.getMessage().contains("HEAD 已变化"),
                "expected message to contain 'HEAD 已变化', got: " + ex.getMessage());

        Mockito.verify(tool, Mockito.never()).call();
        Mockito.verify(repository, Mockito.never())
                .recordBaseline(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void targetPathsDirtyAtExecutionTime_throwsSecurityException() throws Exception {
        GitOperations git = mockGit(tmpRoot, "abc1234", List.of("src/A.java"));

        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        @SuppressWarnings("unchecked")
        Callable<String> tool = Mockito.mock(Callable.class);

        SecurityException ex = Assertions.assertThrows(SecurityException.class,
                () -> guard.execute(p, tool));
        Assertions.assertTrue(ex.getMessage().contains("targetPaths 在 proposal 创建后变 dirty"),
                "expected message to mention dirty targetPaths, got: " + ex.getMessage());

        Mockito.verify(tool, Mockito.never()).call();
    }

    @Test
    void outOfScopeChange_rollbackAndThrow() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of())
                .thenReturn(List.of("src/A.java", "docs/UNRELATED.md"));
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(git.isTracked("docs/UNRELATED.md")).thenReturn(false);
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        SecurityException ex = Assertions.assertThrows(SecurityException.class,
                () -> guard.execute(p, () -> "ok"));
        Assertions.assertTrue(ex.getMessage().contains("工具改动超出授权范围"),
                "expected out-of-scope message, got: " + ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("docs/UNRELATED.md"),
                "expected message to mention docs/UNRELATED.md, got: " + ex.getMessage());

        Mockito.verify(git).checkoutFromSha("abc1234", "src/A.java");
        Mockito.verify(git).deleteFile("docs/UNRELATED.md");

        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
        Mockito.verify(repository, Mockito.never()).recordCommit(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.anyList(), Mockito.anyString());
    }

    @Test
    void noOpWrite_recordsExecutedWithNullCommitSha() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of())
                .thenReturn(List.of());
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.when(repository.recordCommit(Mockito.anyString(), Mockito.any(),
                Mockito.anyList(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        String result = guard.execute(p, () -> "ok");

        Assertions.assertEquals("ok", result);

        Mockito.verify(repository).recordCommit(
                "tip-test-1", null, List.of(), "no-op write; nothing committed");
        Mockito.verify(git, Mockito.never()).add(Mockito.anyList());
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    void toolException_rollbackTargetsAndPropagate() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly()).thenReturn(List.of());
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> guard.execute(p, () -> { throw new RuntimeException("tool boom"); }));
        Assertions.assertEquals("tool boom", ex.getMessage());

        Mockito.verify(git).checkoutFromSha("abc1234", "src/A.java");
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    void previewSummaryWithPercentSign_doesNotThrow() throws Exception {
        GitOperations git = mockGit(tmpRoot, "abc1234", List.of());
        Mockito.when(git.headSha()).thenReturn("abc1234").thenReturn("def5678");
        Mockito.when(git.statusNameOnly()).thenReturn(List.of()).thenReturn(List.of("src/A.java"));
        Mockito.when(git.commit(Mockito.anyString())).thenReturn("def5678");
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.when(repository.recordCommit(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyList(), Mockito.anyString())).thenReturn(true);

        ToolInvocationProposal p = proposalWithPreview(
                "abc1234", List.of("src/A.java"),
                "workspaceRunCommand: mvn -Dpassword=foo%bar deploy");
        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);

        assertThatNoException().isThrownBy(() -> guard.execute(p, () -> "ok"));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        Mockito.verify(git).commit(msg.capture());
        assertThat(msg.getValue()).contains("foo%bar");
    }

    @Test
    void recordBaselineReturnsFalse_throwsBeforeToolCall() throws Exception {
        GitOperations git = mockGit(tmpRoot, "abc1234", List.of());
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));
        Mockito.when(repository.recordBaseline("tip-test-1", "abc1234")).thenReturn(false);
        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);

        @SuppressWarnings("unchecked")
        Callable<String> tool = Mockito.mock(Callable.class);

        assertThatThrownBy(() -> guard.execute(p, tool))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("baseline 写入时已变化");

        Mockito.verify(tool, Mockito.never()).call();
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    void outOfScopePartialRollbackFailure_appearsInExceptionMessage() throws Exception {
        GitOperations git = mockGit(tmpRoot, "abc1234", List.of());
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of())
                .thenReturn(List.of("src/A.java", "docs/UNRELATED.md"));
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(git.isTracked("docs/UNRELATED.md")).thenReturn(false);
        // Make rollback of src/A.java fail
        Mockito.doThrow(new RuntimeException("git checkout boom"))
                .when(git).checkoutFromSha(Mockito.anyString(), Mockito.eq("src/A.java"));
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));
        WorkspaceGitGuard guard = new WorkspaceGitGuard(git, pathNormalizer, repository);

        assertThatThrownBy(() -> guard.execute(p, () -> "ok"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("工具改动超出授权范围")
                .hasMessageContaining("rollback 部分失败")
                .hasMessageContaining("src/A.java");
    }

    private ToolInvocationProposal proposalWithPreview(String headSha, List<String> targetPaths, String preview) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                null, "tip-test-1", "req-1", "run-1",
                "session-A", "user-1", "USER",
                "WorkspaceEditToolPack.workspaceRunCommand", "workspace",
                "[]", "deadbeef",
                "dangerous", targetPaths, preview,
                false, List.of(),
                ToolInvocationProposalStatus.EXECUTING, 0,
                null, null, null,
                headSha, null, null, List.of(),
                null, null,
                now, now, now.plusMinutes(15)
        );
    }
}
