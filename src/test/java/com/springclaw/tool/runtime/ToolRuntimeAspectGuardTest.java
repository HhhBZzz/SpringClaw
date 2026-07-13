package com.springclaw.tool.runtime;

import com.springclaw.service.auth.ToolPermissionService;
import com.springclaw.service.proposal.ApprovedProposalContext;
import com.springclaw.service.proposal.PendingToolApprovalException;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.service.proposal.ToolInvocationSnapshot;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.service.proposal.ToolGateway;
import com.springclaw.service.workspace.WorkspaceGitGuard;
import com.springclaw.tool.pack.FileToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WorkspaceEditToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Task 6 守护测试：验证 ToolRuntimeAspect 在 read / write 工具下的最终风险门禁行为。
 *
 * 不变量覆盖：
 *   - 不变量 4：Aspect 仅信任 ToolExecutionContextHolder 中的 ApprovedProposalContext
 *   - 不变量 5：DB 二次校验 status==EXECUTING
 *   - 不变量 6：argsHash 复算 + 比对
 *   - 不变量 11 的 Aspect 侧：未确认必须创建 PENDING + 抛 PendingToolApprovalException
 */
class ToolRuntimeAspectGuardTest {

    private ToolGuardService toolGuardService;
    private ToolAuditService toolAuditService;
    private ToolPermissionService toolPermissionService;
    private CapabilityRegistry capabilityRegistry;
    private ToolInvocationSnapshotService snapshotService;
    private ToolInvocationProposalService proposalService;
    private ToolGateway toolGateway;
    private WorkspaceGitGuard workspaceGitGuard;
    private ToolRuntimeAspect aspect;

    @BeforeEach
    void setUp() {
        toolGuardService = Mockito.mock(ToolGuardService.class);
        toolAuditService = Mockito.mock(ToolAuditService.class);
        toolPermissionService = Mockito.mock(ToolPermissionService.class);
        capabilityRegistry = Mockito.mock(CapabilityRegistry.class);
        snapshotService = Mockito.mock(ToolInvocationSnapshotService.class);
        proposalService = Mockito.mock(ToolInvocationProposalService.class);
        toolGateway = Mockito.mock(ToolGateway.class);
        workspaceGitGuard = Mockito.mock(WorkspaceGitGuard.class);
        aspect = new ToolRuntimeAspect(
                toolGuardService, toolAuditService, toolPermissionService,
                capabilityRegistry, snapshotService, proposalService, workspaceGitGuard, toolGateway
        );
        ToolExecutionContextHolder.clearApprovedProposal();
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContextHolder.clearApprovedProposal();
    }

    private ProceedingJoinPoint pjpForReadTool(Object[] args) {
        return buildPjp(WorkspaceSearchToolPack.class, "findFilesByName", args);
    }

    private ProceedingJoinPoint pjpForWriteTool(Object[] args) {
        return buildPjp(WorkspaceEditToolPack.class, "workspaceWriteFile", args);
    }

    private ProceedingJoinPoint pjpForFileReadTool(Object[] args) {
        return buildPjp(FileToolPack.class, "readTextFile", args);
    }

    private ProceedingJoinPoint buildPjp(Class<?> declaring, String name, Object[] args) {
        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        MethodSignature sig = Mockito.mock(MethodSignature.class);
        Mockito.when(pjp.getSignature()).thenReturn(sig);
        Mockito.when(pjp.getArgs()).thenReturn(args);
        Mockito.<Class<?>>when(sig.getDeclaringType()).thenReturn(declaring);
        Mockito.when(sig.getName()).thenReturn(name);
        return pjp;
    }

    private ToolInvocationProposal proposal(ToolInvocationProposalStatus status,
                                            String toolName,
                                            String requestId,
                                            String runId,
                                            String userId,
                                            String argsHash) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                1L, "tip-1", requestId, runId,
                "session-A", userId, "USER",
                toolName, "workspace",
                "[\"docs/a.md\"]", argsHash,
                "write", List.of("docs/a.md"), "preview",
                false, List.of(),
                status, 0,
                null, null, null,
                "abc1234", null, null, List.of(),
                null, null,
                now, now, now.plusMinutes(15)
        );
    }

    private ToolInvocationSnapshot stubSnapshot() {
        return new ToolInvocationSnapshot(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                "[\"docs/a.md\"]",
                "abcd1234",
                "write",
                List.of("docs/a.md"),
                "preview",
                false,
                Set.of(),
                "abc1234"
        );
    }

    @Test
    void readTool_proceedsDirectly() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceSearchToolPack"))
                .thenReturn("read");
        ProceedingJoinPoint pjp = pjpForReadTool(new Object[]{"keyword"});
        Mockito.when(pjp.proceed()).thenReturn("result-string");

        Object result = aspect.aroundTool(pjp);

        Assertions.assertEquals("result-string", result);
        Mockito.verify(pjp, Mockito.times(1)).proceed();
        Mockito.verifyNoInteractions(snapshotService);
        Mockito.verifyNoInteractions(proposalService);
        Mockito.verifyNoInteractions(workspaceGitGuard);
    }

    @Test
    void fileReadTool_proceedsDirectlyDespitePackWriteRisk() throws Throwable {
        ProceedingJoinPoint pjp = pjpForFileReadTool(new Object[]{"docs/a.md"});
        Mockito.when(pjp.proceed()).thenReturn("file content");

        Object result = aspect.aroundTool(pjp);

        Assertions.assertEquals("file content", result);
        Mockito.verify(pjp, Mockito.times(1)).proceed();
        Mockito.verify(capabilityRegistry, Mockito.never()).findRiskLevelByClassName("FileToolPack");
        Mockito.verifyNoInteractions(snapshotService);
        Mockito.verifyNoInteractions(proposalService);
        Mockito.verifyNoInteractions(workspaceGitGuard);
    }

    @Test
    void systemRunCommand_noApproved_throwsPendingAndCreatesExecutionProposal() throws Throwable {
        Mockito.when(capabilityRegistry.findToolsetByClassName("SystemToolPack"))
                .thenReturn("system");
        ToolInvocationSnapshot snapshot = new ToolInvocationSnapshot(
                "SystemToolPack.runCommand",
                "system",
                "[\"echo springclaw-approval-e2e\"]",
                "command-hash",
                "execution",
                List.of(),
                "runCommand: echo springclaw-approval-e2e",
                false,
                Set.of(),
                "abc1234"
        );
        Mockito.when(snapshotService.capture(
                Mockito.eq("SystemToolPack.runCommand"),
                Mockito.eq("system"),
                ArgumentMatchers.<Object[]>argThat(args -> args.length == 1
                        && "echo springclaw-approval-e2e".equals(args[0])),
                Mockito.eq("execution")))
                .thenReturn(snapshot);
        ToolInvocationProposal pending = proposal(ToolInvocationProposalStatus.PENDING,
                "SystemToolPack.runCommand", "req-x", "run-x", "user-x", "command-hash");
        Mockito.when(toolGateway.requestApproval(Mockito.eq(snapshot), ArgumentMatchers.any()))
                .thenReturn(pending);

        SystemToolPack target = new SystemToolPack(true, "whitelist", "echo", "", 5, 2000);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(aspect);
        SystemToolPack systemToolPack = proxyFactory.getProxy();

        PendingToolApprovalException ex = Assertions.assertThrows(
                PendingToolApprovalException.class,
                () -> systemToolPack.runCommand("echo springclaw-approval-e2e")
        );

        Assertions.assertEquals("tip-1", ex.proposalId());
        Mockito.verify(snapshotService).capture(
                Mockito.eq("SystemToolPack.runCommand"),
                Mockito.eq("system"),
                ArgumentMatchers.<Object[]>argThat(args -> args.length == 1
                        && "echo springclaw-approval-e2e".equals(args[0])),
                Mockito.eq("execution")
        );
    }

    @Test
    void executionRisk_noApproved_throwsPendingAndCreatesProposal() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("execution");
        Mockito.when(capabilityRegistry.findToolsetByClassName("WorkspaceEditToolPack"))
                .thenReturn("workspace");
        ToolInvocationSnapshot snap = new ToolInvocationSnapshot(
                "WorkspaceEditToolPack.workspaceWriteFile",
                "workspace",
                "[\"docs/a.md\"]",
                "abcd1234",
                "execution",
                List.of("docs/a.md"),
                "preview",
                false,
                Set.of(),
                "abc1234"
        );
        Mockito.when(snapshotService.capture(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("workspace"),
                ArgumentMatchers.any(),
                Mockito.eq("execution"))
        ).thenReturn(snap);
        ToolInvocationProposal pending = proposal(ToolInvocationProposalStatus.PENDING,
                "WorkspaceEditToolPack.workspaceWriteFile",
                "req-x", "run-x", "user-x", "abcd1234");
        Mockito.when(toolGateway.requestApproval(Mockito.eq(snap), ArgumentMatchers.any()))
                .thenReturn(pending);

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        PendingToolApprovalException ex = Assertions.assertThrows(
                PendingToolApprovalException.class,
                () -> aspect.aroundTool(pjp)
        );
        Assertions.assertEquals("tip-1", ex.proposalId());

        Mockito.verify(pjp, Mockito.never()).proceed();
        Mockito.verify(snapshotService).capture(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("workspace"),
                ArgumentMatchers.any(),
                Mockito.eq("execution")
        );
        Mockito.verify(toolGateway).requestApproval(Mockito.eq(snap), ArgumentMatchers.any());
    }

    @Test
    void writeTool_noApproved_throwsPendingAndCreatesProposal() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");
        Mockito.when(capabilityRegistry.findToolsetByClassName("WorkspaceEditToolPack"))
                .thenReturn("workspace");
        ToolInvocationSnapshot snap = stubSnapshot();
        Mockito.when(snapshotService.capture(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("workspace"),
                ArgumentMatchers.any(),
                Mockito.eq("write"))
        ).thenReturn(snap);

        ToolInvocationProposal pending = proposal(ToolInvocationProposalStatus.PENDING,
                "WorkspaceEditToolPack.workspaceWriteFile",
                "req-x", "run-x", "user-x", "abcd1234");
        Mockito.when(toolGateway.requestApproval(Mockito.eq(snap), ArgumentMatchers.any()))
                .thenReturn(pending);

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        PendingToolApprovalException ex = Assertions.assertThrows(
                PendingToolApprovalException.class,
                () -> aspect.aroundTool(pjp)
        );
        Assertions.assertEquals("tip-1", ex.proposalId());

        Mockito.verify(pjp, Mockito.never()).proceed();
        Mockito.verify(snapshotService).capture(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("workspace"),
                ArgumentMatchers.any(),
                Mockito.eq("write")
        );
        Mockito.verify(toolGateway).requestApproval(Mockito.eq(snap), ArgumentMatchers.any());
        // 审计：写到 PENDING_APPROVAL（不是 FAILED）
        Mockito.verify(toolAuditService).recordInvoke(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("PENDING_APPROVAL"),
                Mockito.contains("tip-1"),
                ArgumentMatchers.any()
        );
        Mockito.verify(workspaceGitGuard, Mockito.never())
                .execute(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void writeTool_withApproved_executesViaGitGuard() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");
        Mockito.when(capabilityRegistry.findToolsetByClassName("WorkspaceEditToolPack"))
                .thenReturn("workspace");

        ApprovedProposalContext approved = new ApprovedProposalContext(
                "tip-1", "req-1", "run-1", "u1",
                "WorkspaceEditToolPack.workspaceWriteFile", "abcd1234"
        );
        ToolExecutionContextHolder.setApprovedProposal(approved);

        ToolInvocationProposal latest = proposal(ToolInvocationProposalStatus.EXECUTING,
                "WorkspaceEditToolPack.workspaceWriteFile",
                "req-1", "run-1", "u1", "abcd1234");
        Mockito.when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(latest));
        Mockito.when(snapshotService.argsHash(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("workspace"),
                ArgumentMatchers.any())
        ).thenReturn("abcd1234");
        Mockito.when(workspaceGitGuard.execute(Mockito.eq(latest), ArgumentMatchers.any()))
                .thenReturn("tool-result");

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        Object result = aspect.aroundTool(pjp);

        Assertions.assertEquals("tool-result", result);
        Mockito.verify(workspaceGitGuard, Mockito.times(1))
                .execute(Mockito.eq(latest), ArgumentMatchers.any());
        Mockito.verify(proposalService, Mockito.never()).markFailed(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void writeTool_argsHashMismatch_securityExceptionAndMarkFailed() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");
        Mockito.when(capabilityRegistry.findToolsetByClassName("WorkspaceEditToolPack"))
                .thenReturn("workspace");

        ApprovedProposalContext approved = new ApprovedProposalContext(
                "tip-1", "req-1", "run-1", "u1",
                "WorkspaceEditToolPack.workspaceWriteFile", "abcd1234"
        );
        ToolExecutionContextHolder.setApprovedProposal(approved);

        ToolInvocationProposal latest = proposal(ToolInvocationProposalStatus.EXECUTING,
                "WorkspaceEditToolPack.workspaceWriteFile",
                "req-1", "run-1", "u1", "abcd1234");
        Mockito.when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(latest));
        Mockito.when(snapshotService.argsHash(
                Mockito.eq("WorkspaceEditToolPack.workspaceWriteFile"),
                Mockito.eq("workspace"),
                ArgumentMatchers.any())
        ).thenReturn("different-hash");

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "tampered"});

        SecurityException ex = Assertions.assertThrows(
                SecurityException.class, () -> aspect.aroundTool(pjp));
        Assertions.assertTrue(ex.getMessage().contains("args 被篡改"));
        Mockito.verify(proposalService).markFailed("tip-1", "args 被篡改");
        Mockito.verify(workspaceGitGuard, Mockito.never())
                .execute(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void writeTool_dbStatusNotExecuting_securityException() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");

        ApprovedProposalContext approved = new ApprovedProposalContext(
                "tip-1", "req-1", "run-1", "u1",
                "WorkspaceEditToolPack.workspaceWriteFile", "abcd1234"
        );
        ToolExecutionContextHolder.setApprovedProposal(approved);

        ToolInvocationProposal latest = proposal(ToolInvocationProposalStatus.APPROVED,
                "WorkspaceEditToolPack.workspaceWriteFile",
                "req-1", "run-1", "u1", "abcd1234");
        Mockito.when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(latest));

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        SecurityException ex = Assertions.assertThrows(
                SecurityException.class, () -> aspect.aroundTool(pjp));
        Assertions.assertTrue(ex.getMessage().contains("状态非法"),
                "expected 状态非法 message but was: " + ex.getMessage());
        Mockito.verify(workspaceGitGuard, Mockito.never())
                .execute(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void writeTool_proposalNotFound_securityException() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");

        ApprovedProposalContext approved = new ApprovedProposalContext(
                "ghost-id", "req-1", "run-1", "u1",
                "WorkspaceEditToolPack.workspaceWriteFile", "abcd1234"
        );
        ToolExecutionContextHolder.setApprovedProposal(approved);
        Mockito.when(proposalService.findByProposalId("ghost-id")).thenReturn(Optional.empty());

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        SecurityException ex = Assertions.assertThrows(
                SecurityException.class, () -> aspect.aroundTool(pjp));
        Assertions.assertTrue(ex.getMessage().contains("proposal 不存在"),
                "expected proposal 不存在 but was: " + ex.getMessage());
    }

    @Test
    void writeTool_toolNameMismatch_securityException() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");

        ApprovedProposalContext approved = new ApprovedProposalContext(
                "tip-1", "req-1", "run-1", "u1",
                "WorkspaceEditToolPack.workspaceApplyPatch", "abcd1234"
        );
        ToolExecutionContextHolder.setApprovedProposal(approved);

        // DB 行 toolName 与当前调用 (workspaceWriteFile) 不一致
        ToolInvocationProposal latest = proposal(ToolInvocationProposalStatus.EXECUTING,
                "WorkspaceEditToolPack.workspaceApplyPatch",
                "req-1", "run-1", "u1", "abcd1234");
        Mockito.when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(latest));

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        SecurityException ex = Assertions.assertThrows(
                SecurityException.class, () -> aspect.aroundTool(pjp));
        Assertions.assertTrue(ex.getMessage().contains("toolName 不匹配"),
                "expected toolName 不匹配 but was: " + ex.getMessage());
        Mockito.verify(proposalService).markFailed("tip-1", "toolName 不匹配");
    }

    @Test
    void writeTool_userIdMismatch_securityException() throws Throwable {
        Mockito.when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                .thenReturn("write");

        ApprovedProposalContext approved = new ApprovedProposalContext(
                "tip-1", "req-1", "run-1", "u1",
                "WorkspaceEditToolPack.workspaceWriteFile", "abcd1234"
        );
        ToolExecutionContextHolder.setApprovedProposal(approved);

        ToolInvocationProposal latest = proposal(ToolInvocationProposalStatus.EXECUTING,
                "WorkspaceEditToolPack.workspaceWriteFile",
                "req-1", "run-1", "u2", "abcd1234");
        Mockito.when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(latest));

        ProceedingJoinPoint pjp = pjpForWriteTool(new Object[]{"docs/a.md", "content"});

        SecurityException ex = Assertions.assertThrows(
                SecurityException.class, () -> aspect.aroundTool(pjp));
        Assertions.assertTrue(ex.getMessage().contains("userId 不匹配"),
                "expected userId 不匹配 but was: " + ex.getMessage());
        Mockito.verify(proposalService).markFailed("tip-1", "userId 不匹配");
    }
}
