package com.springclaw.architecture;

import com.springclaw.service.auth.ToolPermissionService;
import com.springclaw.service.proposal.PendingToolApprovalException;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.service.proposal.ToolInvocationSnapshot;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.service.proposal.ToolGateway;
import com.springclaw.service.workspace.WorkspaceGitGuard;
import com.springclaw.tool.pack.WorkspaceEditToolPack;
import com.springclaw.tool.runtime.CapabilityRegistry;
import com.springclaw.tool.runtime.ToolAuditService;
import com.springclaw.tool.runtime.ToolGuardService;
import com.springclaw.tool.runtime.ToolRuntimeAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterizes the proposal boundary implemented by {@link ToolRuntimeAspect}.
 *
 * <p>The assertions invoke the production aspect instead of duplicating its
 * risk predicate. This keeps the test sensitive to changes in write and
 * dangerous proposal enforcement.
 */
class ToolSafetyPathCharacterizationTest {

    private static final String TOOL_NAME = "WorkspaceEditToolPack.workspaceWriteFile";
    private static final Object[] TOOL_ARGS = {"docs/a.md", "content"};

    @ParameterizedTest(name = "{0} requires a pending proposal")
    @ValueSource(strings = {"write", "side_effect", "dangerous", "execution"})
    @DisplayName("Current mutating and execution risks create a proposal instead of invoking the tool")
    void proposalRisksCreatePendingProposal(String riskLevel) throws Throwable {
        Fixture fixture = new Fixture(riskLevel);
        ToolInvocationSnapshot snapshot = snapshot(riskLevel);
        ToolInvocationProposal pending = pendingProposal(riskLevel);
        when(fixture.snapshotService.capture(
                eq(TOOL_NAME), eq("workspace"), any(), eq(riskLevel)))
                .thenReturn(snapshot);
        when(fixture.toolGateway.requestApproval(eq(snapshot), any()))
                .thenReturn(pending);

        assertThatThrownBy(() -> fixture.aspect.aroundTool(fixture.joinPoint))
                .isInstanceOf(PendingToolApprovalException.class)
                .extracting(ex -> ((PendingToolApprovalException) ex).proposalId())
                .isEqualTo("tip-characterization");

        verify(fixture.joinPoint, never()).proceed();
        verify(fixture.snapshotService).capture(
                eq(TOOL_NAME), eq("workspace"), any(), eq(riskLevel));
        verify(fixture.toolGateway).requestApproval(eq(snapshot), any());
        verify(fixture.auditService).recordInvoke(
                eq(TOOL_NAME), eq("PENDING_APPROVAL"),
                eq("proposalId=tip-characterization"), any());
        verifyNoInteractions(fixture.workspaceGitGuard);
    }

    @Test
    @DisplayName("Read risk invokes the tool directly without proposal state")
    void readRiskProceedsWithoutProposal() throws Throwable {
        Fixture fixture = new Fixture("read");
        when(fixture.joinPoint.proceed()).thenReturn("read-result");

        Object result = fixture.aspect.aroundTool(fixture.joinPoint);

        assertThat(result).isEqualTo("read-result");
        verify(fixture.joinPoint).proceed();
        verifyNoInteractions(fixture.snapshotService, fixture.proposalService,
                fixture.toolGateway, fixture.workspaceGitGuard);
    }

    @ParameterizedTest(name = "unclassified risk [{0}] remains on the direct path")
    @NullAndEmptySource
    @DisplayName("Absent risk classification currently invokes the tool without a proposal")
    void absentRiskProceedsWithoutProposal(String riskLevel) throws Throwable {
        Fixture fixture = new Fixture(riskLevel);
        when(fixture.joinPoint.proceed()).thenReturn("unclassified-result");

        Object result = fixture.aspect.aroundTool(fixture.joinPoint);

        assertThat(result).isEqualTo("unclassified-result");
        verify(fixture.joinPoint).proceed();
        verifyNoInteractions(fixture.snapshotService, fixture.proposalService,
                fixture.toolGateway, fixture.workspaceGitGuard);
    }

    private static ToolInvocationSnapshot snapshot(String riskLevel) {
        return new ToolInvocationSnapshot(
                TOOL_NAME,
                "workspace",
                "[\"docs/a.md\",\"content\"]",
                "args-hash",
                riskLevel,
                List.of("docs/a.md"),
                "preview",
                false,
                Set.of(),
                "head-sha"
        );
    }

    private static ToolInvocationProposal pendingProposal(String riskLevel) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                1L,
                "tip-characterization",
                "request-1",
                "run-1",
                "session-1",
                "user-1",
                "USER",
                TOOL_NAME,
                "workspace",
                "[\"docs/a.md\",\"content\"]",
                "args-hash",
                riskLevel,
                List.of("docs/a.md"),
                "preview",
                false,
                List.of(),
                ToolInvocationProposalStatus.PENDING,
                0,
                null,
                null,
                null,
                "head-sha",
                null,
                null,
                List.of(),
                null,
                null,
                now,
                now,
                now.plusMinutes(15)
        );
    }

    private static final class Fixture {

        private final ToolAuditService auditService = mock(ToolAuditService.class);
        private final ToolInvocationSnapshotService snapshotService =
                mock(ToolInvocationSnapshotService.class);
        private final ToolInvocationProposalService proposalService =
                mock(ToolInvocationProposalService.class);
        private final ToolGateway toolGateway = mock(ToolGateway.class);
        private final WorkspaceGitGuard workspaceGitGuard = mock(WorkspaceGitGuard.class);
        private final CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        private final ToolRuntimeAspect aspect;

        private Fixture(String riskLevel) {
            ToolGuardService guardService = mock(ToolGuardService.class);
            ToolPermissionService permissionService = mock(ToolPermissionService.class);
            MethodSignature signature = mock(MethodSignature.class);
            when(joinPoint.getSignature()).thenReturn(signature);
            when(joinPoint.getArgs()).thenReturn(TOOL_ARGS);
            when(signature.getDeclaringType()).thenReturn(WorkspaceEditToolPack.class);
            when(signature.getName()).thenReturn("workspaceWriteFile");
            when(capabilityRegistry.findRiskLevelByClassName("WorkspaceEditToolPack"))
                    .thenReturn(riskLevel);
            when(capabilityRegistry.findToolsetByClassName("WorkspaceEditToolPack"))
                    .thenReturn("workspace");
            aspect = new ToolRuntimeAspect(
                    guardService,
                    auditService,
                    permissionService,
                    capabilityRegistry,
                    snapshotService,
                    proposalService,
                    workspaceGitGuard,
                    toolGateway
            );
        }
    }
}
