package com.springclaw.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
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
    private WorkspaceMutationLeaseCoordinator leaseCoordinator;
    private ToolExecutionResultSerializer resultSerializer;
    private WorkspaceMutationLease lease;

    @BeforeEach
    void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("ws-guard-test");
        tmpRoot.toFile().deleteOnExit();
        pathNormalizer = new PathNormalizer();
        repository = Mockito.mock(ToolInvocationProposalRepository.class);
        leaseCoordinator = Mockito.mock(WorkspaceMutationLeaseCoordinator.class);
        resultSerializer = new ToolExecutionResultSerializer(new ObjectMapper());
        lease = new WorkspaceMutationLease(
                "workspace-id", "tip-test-1", 7L, LocalDateTime.now().plusMinutes(5));
        Mockito.doAnswer(invocation -> {
            WorkspaceMutationLeaseCoordinator.LeaseWork<?> work = invocation.getArgument(2);
            return work.execute(lease);
        }).when(leaseCoordinator).executeExclusive(
                Mockito.eq(tmpRoot), Mockito.eq("tip-test-1"), Mockito.any());
    }

    private WorkspaceGitGuard guard(GitOperations git) {
        return new WorkspaceGitGuard(
                git, pathNormalizer, repository, leaseCoordinator, resultSerializer);
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

        WorkspaceGitGuard guard = guard(git);
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
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(repository).recordCommit(
                Mockito.eq("tip-test-1"),
                Mockito.eq("def5678"),
                changedCaptor.capture(),
                resultCaptor.capture());
        Assertions.assertEquals(List.of("src/A.java"), changedCaptor.getValue());
        JsonNode audit = new ObjectMapper().readTree(resultCaptor.getValue());
        assertThat(audit.path("schema").asText()).isEqualTo("springclaw.tool-execution-result.v1");
        assertThat(audit.path("proposalId").asText()).isEqualTo("tip-test-1");
        assertThat(audit.path("toolName").asText())
                .isEqualTo("WorkspaceEditToolPack.workspaceWriteFile");
        assertThat(audit.path("fencingToken").asLong()).isEqualTo(7L);
        assertThat(audit.path("noOp").asBoolean()).isFalse();
        assertThat(audit.path("gitCommitSha").asText()).isEqualTo("def5678");
        assertThat(audit.path("changedFiles").get(0).asText()).isEqualTo("src/A.java");
        assertThat(audit.path("result").asText()).isEqualTo("ok");
        Mockito.verify(leaseCoordinator).assertCurrent(lease);
    }

    @Test
    void baselineMismatch_throwsSecurityException() throws Exception {
        GitOperations git = mockGit(tmpRoot, "xyz0000");

        WorkspaceGitGuard guard = guard(git);
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
        org.mockito.InOrder order = Mockito.inOrder(leaseCoordinator, git);
        order.verify(leaseCoordinator).executeExclusive(
                Mockito.eq(tmpRoot), Mockito.eq("tip-test-1"), Mockito.any());
        order.verify(git).headSha();
    }

    @Test
    void targetPathsDirtyAtExecutionTime_throwsSecurityException() throws Exception {
        GitOperations git = mockGit(tmpRoot, "abc1234", List.of("src/A.java"));

        WorkspaceGitGuard guard = guard(git);
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

        WorkspaceGitGuard guard = guard(git);
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
    void dirtyNonTargetModifiedByTool_restoresSnapshotAndThrows() throws Exception {
        Path notes = tmpRoot.resolve("docs/notes.md");
        Files.createDirectories(notes.getParent());
        Files.writeString(notes, "user dirty before", StandardCharsets.UTF_8);

        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of("docs/notes.md"))
                .thenReturn(List.of("docs/notes.md", "src/A.java"));
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = guard(git);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        assertThatThrownBy(() -> guard.execute(p, () -> {
            Files.writeString(notes, "tool corrupted", StandardCharsets.UTF_8);
            return "ok";
        }))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("docs/notes.md");

        assertThat(Files.readString(notes, StandardCharsets.UTF_8)).isEqualTo("user dirty before");
        Mockito.verify(git).checkoutFromSha("abc1234", "src/A.java");
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
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

        WorkspaceGitGuard guard = guard(git);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        String result = guard.execute(p, () -> "ok");

        Assertions.assertEquals("ok", result);

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(repository).recordCommit(
                Mockito.eq("tip-test-1"), Mockito.isNull(), Mockito.eq(List.of()), resultCaptor.capture());
        JsonNode audit = new ObjectMapper().readTree(resultCaptor.getValue());
        assertThat(audit.path("noOp").asBoolean()).isTrue();
        assertThat(audit.path("gitCommitSha").isNull()).isTrue();
        assertThat(audit.path("result").asText()).isEqualTo("ok");
        Mockito.verify(leaseCoordinator).assertCurrent(lease);
        Mockito.verify(git, Mockito.never()).add(Mockito.anyList());
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    void staleFencingTokenStopsPublishingWithoutMutatingSharedWorkspace() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of())
                .thenReturn(List.of("src/A.java"));
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.doThrow(new SecurityException("fencing token 已失效"))
                .when(leaseCoordinator).assertCurrent(lease);

        WorkspaceGitGuard guard = guard(git);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        assertThatThrownBy(() -> guard.execute(p, () -> "tool-result"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("fencing token 已失效");

        Mockito.verify(git, Mockito.never()).checkoutFromSha(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(git, Mockito.never()).add(Mockito.anyList());
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
        Mockito.verify(repository, Mockito.never()).recordCommit(
                Mockito.anyString(), Mockito.any(), Mockito.anyList(), Mockito.anyString());
    }

    @Test
    void authoritativelyExpiredLeaseRollsBackWhileRowLockIsStillHeld() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of())
                .thenReturn(List.of("src/A.java"));
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.doThrow(new WorkspaceLeaseExpiredException("lease expired"))
                .when(leaseCoordinator).assertCurrent(lease);

        WorkspaceGitGuard guard = guard(git);

        assertThatThrownBy(() -> guard.execute(
                proposal("abc1234", List.of("src/A.java")), () -> "result"))
                .isInstanceOf(WorkspaceLeaseExpiredException.class);

        Mockito.verify(git).checkoutFromSha("abc1234", "src/A.java");
        Mockito.verify(git, Mockito.never()).add(Mockito.anyList());
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    void dirtyNonTargetDirectory_doesNotBlockToolExecution() throws Exception {
        Files.createDirectories(tmpRoot.resolve("docs/interview-prep"));

        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly())
                .thenReturn(List.of("docs/interview-prep"))
                .thenReturn(List.of("docs/interview-prep"));
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.when(repository.recordCommit(Mockito.anyString(), Mockito.any(),
                Mockito.anyList(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = guard(git);
        ToolInvocationProposal p = proposal("abc1234", List.of("Desktop/hello.txt"));

        assertThatNoException().isThrownBy(() -> guard.execute(p, () -> "ok"));

        Mockito.verify(repository).recordCommit(
                Mockito.eq("tip-test-1"), Mockito.isNull(), Mockito.eq(List.of()), Mockito.anyString());
    }

    @Test
    void toolException_rollbackTargetsAndPropagate() throws Exception {
        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly()).thenReturn(List.of());
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = guard(git);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> guard.execute(p, () -> { throw new RuntimeException("tool boom"); }));
        Assertions.assertEquals("tool boom", ex.getMessage());

        Mockito.verify(git).checkoutFromSha("abc1234", "src/A.java");
        Mockito.verify(git, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    void toolException_restoresDirtyNonTargetSnapshotAndPropagates() throws Exception {
        Path notes = tmpRoot.resolve("docs/notes.md");
        Files.createDirectories(notes.getParent());
        Files.writeString(notes, "user dirty before", StandardCharsets.UTF_8);

        GitOperations git = Mockito.mock(GitOperations.class);
        Mockito.when(git.workspaceRoot()).thenReturn(tmpRoot);
        Mockito.when(git.headSha()).thenReturn("abc1234");
        Mockito.when(git.statusNameOnly()).thenReturn(List.of("docs/notes.md"));
        Mockito.when(git.isTracked("src/A.java")).thenReturn(true);
        Mockito.when(repository.recordBaseline(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        WorkspaceGitGuard guard = guard(git);
        ToolInvocationProposal p = proposal("abc1234", List.of("src/A.java"));

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> guard.execute(p, () -> {
                    Files.writeString(notes, "tool corrupted before throwing", StandardCharsets.UTF_8);
                    throw new RuntimeException("tool boom");
                }));
        Assertions.assertEquals("tool boom", ex.getMessage());

        assertThat(Files.readString(notes, StandardCharsets.UTF_8)).isEqualTo("user dirty before");
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
        WorkspaceGitGuard guard = guard(git);

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
        WorkspaceGitGuard guard = guard(git);

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
        WorkspaceGitGuard guard = guard(git);

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
