package com.springclaw.tool.runtime;

import com.springclaw.tool.pack.WorkspaceSearchToolPack;
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
 * 守住不变量 11：被 Spring AOP 代理的 ToolPack bean，
 * 任何对其 @Tool 方法的反射调用都必须触发 ToolRuntimeAspect.aroundTool。
 *
 * 这是 P0 安全模型的根。如果这个测试失败，说明 Spring AI ToolCallback
 * 不再走 Spring proxy，整个 confirm-resume 二次校验路径会失效。
 */
@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class ToolRuntimeAspectInterceptionIT {

    @MockitoSpyBean
    ToolRuntimeAspect aspect;

    @Autowired
    WorkspaceSearchToolPack toolPack;

    @BeforeEach
    void resetSpy() {
        Mockito.clearInvocations(aspect);
    }

    @Test
    void invokingToolMethodOnSpringProxyTriggersAspect() throws Throwable {
        // findFilesByName 是 @Tool 标注的只读工具，参数 keyword
        // Spring AI 在 ToolCallback 内部用 Method.invoke(proxyBean, args)，
        // 这里直接调代理对象的方法模拟同一路径。
        // 故意选不存在的关键词，让方法走 happy path 但不依赖任何文件
        toolPack.findFilesByName("nonexistent-marker-XYZ");

        ArgumentCaptor<ProceedingJoinPoint> jp = ArgumentCaptor.forClass(ProceedingJoinPoint.class);
        verify(aspect, times(1)).aroundTool(jp.capture());
        assertThat(jp.getValue().getSignature().getName()).isEqualTo("findFilesByName");
    }
}
