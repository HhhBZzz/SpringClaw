package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.kernel.AgentLoopKernel;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AutonomousLoopEngine 循环 step 边界埋点单测(Phase 1 Task 6):
 * 每步循环体包 {@code beginStep(requestId, stepNo - 1, "autonomous")}。
 * AutonomousLoop 此前无循环驱动级测试(prompt 渲染在 PromptInjectionTest),此处补最小闭环。
 */
class AutonomousLoopEngineStepBoundaryTest {

    @Test
    void autonomousLoopEmitsStepBoundaryPerIteration() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        RunLifecycleObserver lifecycleObserver = mock(RunLifecycleObserver.class);
        AutonomousLoopEngine engine = new AutonomousLoopEngine(
                mock(AiProviderService.class),
                toolOrchestrator,
                guard,
                executor,
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(ChatGuardService.class),
                lifecycleObserver,
                new AgentLoopKernel(executor, lifecycleObserver),
                true,
                5
        );

        AiProviderService.ActiveChatClient client = new AiProviderService.ActiveChatClient(
                "openai", "gpt-4o", "https://example.test", null, true, "");
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // 第1步输出中间结果,第2步 read 任务 TASK_COMPLETE 直接完成
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(textResult("正在分析问题", client), textResult("TASK_COMPLETE\n结果:X", client));

        engine.execute(autonomousCtx(client), (reason, ctx) -> "fallback");

        // 每步循环体包 beginStep step 边界(kind="autonomous",index 从 0 起)
        verify(lifecycleObserver, times(2)).beginStep(anyString(), anyInt(), eq("autonomous"));
    }

    private ModelCallExecutor.ModelCallResult<String> textResult(
            String value, AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(value, client, java.util.List.of(), false);
    }

    private ChatContext autonomousCtx(AiProviderService.ActiveChatClient client) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test", "user-1", "请分析 X", "", "", "observe");
        return new ChatContext(
                null, "test", "user-1", null,
                "请分析 X", "请分析 X", "req-1", "system",
                assembled, client,
                "autonomous", "autonomous-paradigm", "agent", "general",
                null, null, null, AgentParadigm.AUTONOMOUS_LOOP);
    }
}
