package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReActEngine 骨架单测(Task 1):只锁定接入地基不变量——
 * 声明 REACT 范式、name() 登记 "react-loop"、supports() 仅在 paradigm=REACT 时为真。
 * 循环体(Thought-Action-Observation)留待 Task 3 填充,故此处不驱动 execute/stream。
 */
class ReActEngineTest {

    @Test
    void declaresReactParadigmAndSelectorName() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.paradigm()).isEqualTo(AgentParadigm.REACT);
        assertThat(engine.name()).isEqualTo("react-loop");
    }

    @Test
    void supportsWhenParadigmIsReact() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.REACT))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.OPAR))).isFalse();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.AUTONOMOUS_LOOP))).isFalse();
        assertThat(engine.supports(contextWithParadigm(AgentParadigm.SINGLE_TURN))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newReActEngine().supports(null)).isFalse();
    }

    @Test
    void rendersReActPromptWithThoughtActionObservationToolsAndHistory() {
        ReActEngine engine = newReActEngine();
        Object[] tools = new Object[]{new ReactToolFixture()};
        ChatContext ctx = contextWithParadigm(AgentParadigm.REACT);
        String prompt = engine.renderReActPrompt(ctx, tools, "Thought: t1 Action: search\nObservation: o1", "read");
        assertThat(prompt).contains("Thought", "Action", "Observation"); // ReAct 协议
        assertThat(prompt).contains("search"); // 工具列表
        assertThat(prompt).contains("Thought: t1 Action: search\nObservation: o1"); // history 注入
    }

    @Test
    void buildReActHistoryRendersTriplePerStep() {
        ReActEngine engine = newReActEngine();
        List<ReActEngine.ReActStep> steps = List.of(
                new ReActEngine.ReActStep("推理1", "search(\"q\")", "结果1"),
                new ReActEngine.ReActStep("推理2", "writeFile(p)", "结果2"));
        String history = engine.buildReActHistory(steps);
        assertThat(history).contains("Thought: 推理1", "Action: search(\"q\")", "Observation: 结果1",
                "Thought: 推理2", "Action: writeFile(p)", "Observation: 结果2",
                "Step 1", "Step 2");
    }

    @Test
    void buildReActHistoryEmptyReturnsFirstRoundHint() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.buildReActHistory(List.of())).contains("第一轮"); // 空历史提示
    }

    // === Task 3: runReActLoop 主路径(原生工具调用)循环 ===

    /**
     * 主路径终止:第1步 LLM 输出含 "Action:" 行(发起工具调用)→ 继续;
     * 第2步 LLM 输出纯最终答案(无 "Action:")→ 终止。
     * 驱动阻塞入口 {@code execute},断言循环跑满 2 步、最终答案取第2步文本、Action 轨迹含第1步工具。
     */
    @Test
    void reactLoopRunsUntilLlmGivesFinalAnswerWithoutToolCall() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        AiProviderService.ActiveChatClient client = activeClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        String step1 = "Thought: 需要搜索相关资料\nAction: search(\"Spring AI\")";
        String step2 = "最终答案: 搜索完成,Spring AI 是一个 AI 框架。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(step1, client), callResult(step2, client));

        ChatExecutionResult result = engine.execute(reactContext(client), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.reflect()).contains("Spring AI 是一个 AI 框架"); // 第2步最终答案
        assertThat(result.plan()).contains("2 步"); // 循环跑满 2 步
        assertThat(result.action()).contains("search"); // 第1步 Action 进入轨迹
        verify(executor, times(2)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    /**
     * max-steps 守护:LLM 每步都输出 "Action:"(永不自行终止),
     * 断言达到 maxReactSteps 后返回当前最佳(降级提示),调用次数恰为 maxReactSteps。
     */
    @Test
    void reactLoopStopsAtMaxReactSteps() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 2); // maxReactSteps=2

        AiProviderService.ActiveChatClient client = activeClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // 每步都含 "Action:" → 永不终止,只能靠 max-steps 兜底
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult("Thought: 继续搜索\nAction: search(\"q\")", client));

        ChatExecutionResult result = engine.execute(reactContext(client), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.plan()).contains("2 步"); // 跑满 maxReactSteps
        assertThat(result.reflect()).contains("max-react-steps"); // 降级提示
        verify(executor, times(2)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    /**
     * 测试用工具 fixture:反射扫 {@link Tool} 的目标(模拟真实 tool pack bean)。
     * renderToolList 经 {@code getTargetClass} 取 declaredMethods,命中 search/writeFile。
     */
    static class ReactToolFixture {
        @Tool(name = "search", description = "搜索知识库")
        public String search(String query) {
            return "result";
        }

        @Tool(name = "writeFile", description = "写入文件")
        public String writeFile(String path, String content) {
            return "ok";
        }
    }

    /**
     * 构造 ReActEngine 骨架:mock 11 bean 依赖 + maxReactSteps=6。
     * 依赖签名与 AutonomousLoopEngine 一致(见该类构造函数)。
     */
    private ReActEngine newReActEngine() {
        return new ReActEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(ChatGuardService.class),
                mock(RunLifecycleObserver.class),
                6
        );
    }

    /**
     * Task 3 循环测试用:注入可 stub 的 ModelCallExecutor / ToolOrchestrator / Guard,
     * 其余 bean 仍 mock,可自定义 maxReactSteps。
     */
    private ReActEngine newReActEngine(ModelCallExecutor executor,
                                       ToolOrchestrator toolOrchestrator,
                                       ModelTransportGuardService guard,
                                       int maxReactSteps) {
        return new ReActEngine(
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
                mock(RunLifecycleObserver.class),
                maxReactSteps
        );
    }

    /**
     * 构造可用的 ActiveChatClient(canonical 6 参 record)。chatClient 置 null——
     * ModelCallExecutor 被 mock,真实调用 lambda 不会执行,无需 ChatClient。
     */
    private AiProviderService.ActiveChatClient activeClient() {
        return new AiProviderService.ActiveChatClient(
                "openai", "gpt-4o", "https://example.test", null, true, "");
    }

    /**
     * 构造 ModelCallResult<String>(value=模型输出文本,client=failover 后客户端)。
     * ModelCallExecutor.executeChat 内部丢弃 ChatResponse 只透出文本,故测试只控文本。
     */
    private ModelCallExecutor.ModelCallResult<String> callResult(String text,
                                                                 AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(text, client, List.of(), false);
    }

    /**
     * 构造驱动 runReActLoop 主路径的 ChatContext(canonical 18 参):
     * activeClient 可用、assembled 提供 sessionKey/channel/userId/question/observePrompt,
     * decision=null(runReActLoop 兜底 riskLevel=read)、paradigm=REACT。
     */
    private ChatContext reactContext(AiProviderService.ActiveChatClient client) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test", "user-1", "请搜索 X", "", "", "observe");
        return new ChatContext(
                null, "test", "user-1", null,
                "请搜索 X", "请搜索 X", "req-1", "system",
                assembled, client,
                "react", "react-paradigm", "agent", "general",
                null, null, null, AgentParadigm.REACT);
    }

    /**
     * 构造带 paradigm 的 ChatContext(canonical 18 参构造)。
     * supports() 只读 paradigm,其余字段置 null 即可。
     */
    private ChatContext contextWithParadigm(AgentParadigm paradigm) {
        return new ChatContext(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, paradigm
        );
    }
}
