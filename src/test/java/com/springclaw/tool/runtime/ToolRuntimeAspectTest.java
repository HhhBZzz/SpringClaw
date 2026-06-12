package com.springclaw.tool.runtime;

import com.springclaw.service.auth.ToolPermissionService;
import com.springclaw.service.workspace.WorkspaceGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRuntimeAspectTest {

    @Test
    void shouldRecordWorkspaceGuardDecisionAsStructuredFailureDetail() throws Throwable {
        ToolGuardService toolGuardService = mock(ToolGuardService.class);
        ToolAuditService toolAuditService = mock(ToolAuditService.class);
        ToolPermissionService toolPermissionService = mock(ToolPermissionService.class);
        ToolRuntimeAspect aspect = new ToolRuntimeAspect(toolGuardService, toolAuditService, toolPermissionService);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        ToolExecutionContext context = new ToolExecutionContext("s1", "api", "u1", "req-1", "ACT");
        WorkspaceGuard.Decision decision = new WorkspaceGuard.Decision(
                WorkspaceGuard.Action.REJECT,
                "COMMAND_PARENT_PATH",
                "命令包含父目录路径段，已被拦截",
                null
        );

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(WorkspaceEditToolForAspectTest.class);
        when(signature.getName()).thenReturn("workspaceRunCommand");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenThrow(new WorkspaceGuard.WorkspaceGuardException(40065, decision));

        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    WorkspaceGuard.WorkspaceGuardException.class,
                    () -> aspect.aroundTool(joinPoint));
        }

        verify(toolAuditService).recordInvoke(
                eq("WorkspaceEditToolForAspectTest.workspaceRunCommand"),
                eq("FAILED"),
                contains("\"schema\":\"springclaw.workspace-guard.v1\""),
                eq(context)
        );
        verify(toolAuditService).recordInvoke(
                eq("WorkspaceEditToolForAspectTest.workspaceRunCommand"),
                eq("FAILED"),
                contains("\"reasonCode\":\"COMMAND_PARENT_PATH\""),
                eq(context)
        );
    }

    private static class WorkspaceEditToolForAspectTest {
    }
}
