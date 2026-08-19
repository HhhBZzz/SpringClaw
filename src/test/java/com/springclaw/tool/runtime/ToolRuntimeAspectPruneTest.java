package com.springclaw.tool.runtime;

import com.springclaw.service.auth.ToolPermissionService;
import com.springclaw.service.context.ToolResultPruner;
import com.springclaw.service.proposal.ToolGateway;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.service.workspace.WorkspaceGitGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 剪枝接入端到端:经 ToolRuntimeAspect 的 @Tool 调用,超预算 String 结果
 * 在返回给引擎(→模型上下文)前被剪枝(dsh 二轮差距 #3 落地证明)。
 */
class ToolRuntimeAspectPruneTest {

    static class DummyReadToolPack {
        @org.springframework.ai.tool.annotation.Tool(description = "大输出工具")
        public String bigOutput(String seed) {
            return seed.repeat(10_000);
        }
    }

    @Test
    void oversizedStringResultIsPrunedBeforeReturningToEngine() throws Throwable {
        ToolRuntimeAspect aspect = new ToolRuntimeAspect(
                mock(ToolGuardService.class),
                mock(ToolAuditService.class),
                mock(ToolPermissionService.class),
                mock(CapabilityRegistry.class),
                mock(ToolInvocationSnapshotService.class),
                mock(ToolInvocationProposalService.class),
                mock(WorkspaceGitGuard.class),
                mock(ToolGateway.class),
                new ToolResultPruner()
        );

        Method method = DummyReadToolPack.class.getMethod("bigOutput", String.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getDeclaringType()).thenAnswer(inv -> DummyReadToolPack.class);
        when(signature.getName()).thenReturn("bigOutput");
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{"x"});
        when(pjp.proceed()).thenAnswer(inv -> new DummyReadToolPack().bigOutput("x"));

        Object result = aspect.aroundTool(pjp);

        assertThat(result).isInstanceOf(String.class);
        String text = (String) result;
        // 原输出 10000 字符 → 剪后 ~3000+marker,远小于原文
        assertThat(text.length()).isLessThan(4000);
        assertThat(text).contains(ToolResultPruner.PRUNE_MARKER);
    }
}
