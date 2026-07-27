package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentDecision;
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

    // === Task 5: DeepSeek V4 显式 prompt 回退(Action parse + 手动工具执行) ===

    /**
     * DeepSeek V4(!supportsNativeToolCalling)走显式 prompt 回退:不挂 {@code .tools()},
     * LLM 在文本里输出 Thought + Action,引擎解析 Action 后**手动执行**工具,把结果作为
     * Observation 拼入历史进入下一轮——直到 LLM 给出无 Action 的最终答案。
     * <p>断言要点:
     * <ul>
     *   <li>search 工具被手动执行恰好 1 次(第1步 Action,第2步最终答案无 Action)——
     *       这证明回退路径**真正手动调了 @Tool 方法**,而非依赖 .tools() 原生往返
     *       (原生路径由 Spring AI 内部执行,不经此 fixture 计数);</li>
     *   <li>循环 2 步(2 次 LLM 调用),最终答案取第2步文本;</li>
     *   <li>Action 轨迹含 search(第1步 Action 进入 trace)。</li>
     * </ul>
     */
    @Test
    void deepSeekV4FallsBackToExplicitPromptAndParsesAction() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        // DeepSeek V4 client:DeepSeekChatCompatibility.supportsNativeToolCalling 返回 false
        // (providerId=deepseek + model 含 "deepseekv4")→ 走显式 prompt 回退,不挂 .tools()。
        AiProviderService.ActiveChatClient deepSeek = deepSeekV4Client();
        when(guard.isModelCallEnabled(deepSeek)).thenReturn(true);
        ReactToolFixture fixture = new ReactToolFixture();
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // 第1步:LLM 输出 Thought + 命名参数格式 Action(query="q");第2步:纯最终答案(无 Action)。
        String step1 = "Thought: 需要搜索相关资料\nAction: search(query=\"q\")";
        String step2 = "最终答案: 搜索完成,Spring AI 是一个 AI 框架。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(step1, deepSeek), callResult(step2, deepSeek));

        ChatExecutionResult result = engine.execute(reactContext(deepSeek), (reason, ctx) -> "fallback");

        // 工具被手动执行(回退路径核心证据)
        assertThat(fixture.searchCalls.get())
                .as("DeepSeek V4 回退路径应手动执行 search 工具恰好 1 次")
                .isEqualTo(1);
        // 循环行为
        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.plan()).contains("2 步");
        assertThat(result.reflect()).contains("Spring AI 是一个 AI 框架");
        assertThat(result.action()).contains("search");
        verify(executor, times(2)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    /**
     * 位置参数格式 Action 也要能解析+执行:Action: search("Spring AI")。
     * 校验位置参数绑定(无 name=)正确解析并手动执行工具,拿到真实 Observation。
     */
    @Test
    void deepSeekV4ParsesPositionalActionArgs() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        AiProviderService.ActiveChatClient deepSeek = deepSeekV4Client();
        when(guard.isModelCallEnabled(deepSeek)).thenReturn(true);
        ReactToolFixture fixture = new ReactToolFixture();
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        String step1 = "Thought: 搜索\nAction: search(\"Spring AI\")";
        String step2 = "最终答案: 完成。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(step1, deepSeek), callResult(step2, deepSeek));

        engine.execute(reactContext(deepSeek), (reason, ctx) -> "fallback");

        assertThat(fixture.searchCalls.get()).isEqualTo(1);
    }

    /**
     * Markdown 修饰的 Action 行(**Action:** / - Action: 等)也要识别为工具调用。
     * 校验 hasActionLine / findActionLine 借鉴 normalizeMarkerLine 的容错。
     */
    @Test
    void deepSeekV4RecognizesMarkdownBoldActionLine() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        AiProviderService.ActiveChatClient deepSeek = deepSeekV4Client();
        when(guard.isModelCallEnabled(deepSeek)).thenReturn(true);
        ReactToolFixture fixture = new ReactToolFixture();
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // **Action:** bold + list prefix + trailingcolon noise
        String step1 = "Thought: 需要搜索\n- **Action:** search(query=\"q\").";
        String step2 = "最终答案: 完成。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(step1, deepSeek), callResult(step2, deepSeek));

        engine.execute(reactContext(deepSeek), (reason, ctx) -> "fallback");

        assertThat(fixture.searchCalls.get())
                .as("markdown 修饰的 **Action:** 行应被识别并执行")
                .isEqualTo(1);
    }

    /**
     * DeepSeek V4 + 工具找不到时的优雅降级:LLM 输出一个不存在的工具名,
     * 引擎不抛异常,Observation 记录错误说明,循环继续(下一步 LLM 给最终答案)。
     */
    @Test
    void deepSeekV4UnknownToolDoesNotCrashLoop() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        AiProviderService.ActiveChatClient deepSeek = deepSeekV4Client();
        when(guard.isModelCallEnabled(deepSeek)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{new ReactToolFixture()});

        String step1 = "Thought: 调用不存在的工具\nAction: nonexistentTool(foo=\"bar\")";
        String step2 = "最终答案: 已放弃该工具,直接回答。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(step1, deepSeek), callResult(step2, deepSeek));

        ChatExecutionResult result = engine.execute(reactContext(deepSeek), (reason, ctx) -> "fallback");

        // 不崩溃,循环正常推进到第2步终止
        assertThat(result.plan()).contains("2 步");
        assertThat(result.reflect()).contains("已放弃该工具");
    }

    /**
     * 原生路径回归(supportsNativeToolCalling=true):即便 LLM 文本含 "Action:" 行,
     * 引擎也**不应**手动执行工具(Spring AI 已在 .call() 内完成往返)。
     * 校验 fallback 执行仅发生在 !supportsNativeToolCalling 分支。
     */
    @Test
    void nativePathDoesNotManuallyExecuteEvenWhenActionLinePresent() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        // openai/gpt-4o → supportsNativeToolCalling=true → 原生路径
        AiProviderService.ActiveChatClient client = activeClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        ReactToolFixture fixture = new ReactToolFixture();
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // 原生路径下 LLM 仍可能输出 "Action:" 文本 —— 不应触发手动执行
        String step1 = "Thought: 调用工具\nAction: search(query=\"q\")";
        String step2 = "最终答案: 完成。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(step1, client), callResult(step2, client));

        engine.execute(reactContext(client), (reason, ctx) -> "fallback");

        assertThat(fixture.searchCalls.get())
                .as("原生路径下不应手动执行工具(Spring AI 内部处理)")
                .isEqualTo(0);
    }

    // === Task 4: 假完成守护(write/side_effect/dangerous 校验工具证据) ===

    /**
     * 假完成拦截:写任务(decision.riskLevel=write),LLM 每步直接给"最终答案"文本(无 Action 行),
     * 但 mock 不触发真实工具 → tracker 无写证据 → satisfiesCompletionCondition=false → 拒绝完成。
     * 断言:循环未在第1步终止(跑到 max-steps=2,2 次调用),降级提示而非 LLM 的假答案。
     */
    @Test
    void writeTaskRejectsFakeCompletionWithoutToolEvidence() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 2); // maxReactSteps=2

        AiProviderService.ActiveChatClient client = activeClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // 每步都给"最终答案"文本(无 "Action:" 行)→ hasActionLine=false → 进入完成判定
        String fakeFinal = "最终答案: 我已经创建了文件 hello.txt。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(fakeFinal, client));

        AgentDecision writeDecision = new AgentDecision(
                "general", "basic_model", List.of(), "write", false, "test write task");
        ChatExecutionResult result = engine.execute(
                reactContext(client, writeDecision), (reason, ctx) -> "fallback");

        // 假完成被拦截:循环未在第1步终止,继续到 max-steps=2(2 次调用)
        verify(executor, times(2)).executeChat(any(), anyString(), any(), anyBoolean(), any());
        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.plan()).contains("2 步"); // 跑满 maxReactSteps
        assertThat(result.reflect()).contains("max-react-steps"); // 降级提示
        assertThat(result.reflect()).doesNotContain("已经创建了文件"); // 假答案未透传给用户
    }

    /**
     * 反向校验:read 任务(riskLevel=read),LLM 第1步直接给最终答案(无 Action 行),
     * read 不校验工具证据 → 立即完成(第1步终止),不会被假完成守护拦下。
     */
    @Test
    void readTaskCompletesImmediatelyWithoutToolEvidence() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReActEngine engine = newReActEngine(executor, toolOrchestrator, guard, 6);

        AiProviderService.ActiveChatClient client = activeClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        String finalAnswer = "最终答案: 这是一个只读分析结论。";
        when(executor.<String>executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(callResult(finalAnswer, client));

        // decision=null → runReActLoop 兜底 riskLevel=read
        ChatExecutionResult result = engine.execute(reactContext(client), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.plan()).contains("1 步"); // 第1步即完成
        assertThat(result.reflect()).contains("只读分析结论"); // 真答案透传
        verify(executor, times(1)).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    /**
     * 测试用工具 fixture:反射扫 {@link Tool} 的目标(模拟真实 tool pack bean)。
     * renderToolList 经 {@code getTargetClass} 取 declaredMethods,命中 search/writeFile。
     * Task 5:searchCalls 计数器用于断言 DeepSeek V4 显式回退路径**手动执行**了工具
     * (而非 .tools() 原生路径——后者由 Spring AI 内部调,不会经过这里的计数)。
     */
    static class ReactToolFixture {
        final java.util.concurrent.atomic.AtomicInteger searchCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Tool(name = "search", description = "搜索知识库")
        public String search(String query) {
            searchCalls.incrementAndGet();
            return "search-result:" + query;
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
     * Task 5 用:构造 DeepSeek V4 client——providerId=deepseek + model=deepseek-v4,
     * DeepSeekChatCompatibility.supportsNativeToolCalling 对此返回 false
     * (isDeepSeekV4 归一化 "deepseek-v4" → "deepseekv4" 含 "deepseekv4"),
     * 驱动 runReActLoop 走显式 prompt 回退而非 .tools() 原生路径。无需 static mock。
     */
    private AiProviderService.ActiveChatClient deepSeekV4Client() {
        return new AiProviderService.ActiveChatClient(
                "deepseek", "deepseek-v4", "https://example.test", null, true, "");
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
        return reactContext(client, null);
    }

    /**
     * 带 AgentDecision 的 reactContext 重载:Task 4 假完成测试用它注入 riskLevel=write 等。
     */
    private ChatContext reactContext(AiProviderService.ActiveChatClient client, AgentDecision decision) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test", "user-1", "请搜索 X", "", "", "observe");
        return new ChatContext(
                null, "test", "user-1", null,
                "请搜索 X", "请搜索 X", "req-1", "system",
                assembled, client,
                "react", "react-paradigm", "agent", "general",
                decision, null, null, AgentParadigm.REACT);
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
