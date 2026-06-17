package com.springclaw.service.proposal;

import com.springclaw.tool.runtime.ToolRuntimeAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 守住不变量 11 执行侧：SpringToolInvoker 通过 CGLIB 代理反射调用 @Tool 方法时，
 * 必须触发 ToolRuntimeAspect.aroundTool。如果未来某次重构让 invoker 拿到了原始
 * 目标实例（绕过代理），confirm-resume 路径上的二次校验失效，整个 P0 安全模型崩塌。
 */
@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class SpringToolInvokerIT {

    @MockitoSpyBean
    ToolRuntimeAspect aspect;

    @Autowired
    SpringToolInvoker invoker;

    @BeforeEach
    void resetSpy() {
        Mockito.clearInvocations(aspect);
    }

    @Test
    void invokeRoutesThroughSpringProxyAndTriggersAspect() throws Throwable {
        // findFilesByName 是 read 工具，走 happy path（read 在 Aspect 内不进 proposal 分支）
        invoker.invoke("WorkspaceSearchToolPack.findFilesByName", "[\"nonexistent-marker-XYZ\"]");

        ArgumentCaptor<ProceedingJoinPoint> jp = ArgumentCaptor.forClass(ProceedingJoinPoint.class);
        verify(aspect, times(1)).aroundTool(jp.capture());
        assertThat(jp.getValue().getSignature().getName()).isEqualTo("findFilesByName");
    }
}