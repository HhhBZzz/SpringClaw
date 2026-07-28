package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReflexionEngine 单测(RX-T1 骨架 + RX-T2 prompt/ReflectionResult + RX-T3 循环/守护/SSE)——验证
 * paradigm/name/supports 接入地基 + renderAttemptPrompt/renderReflectionPrompt 模板注入 +
 * ReflectionResult(BeanOutputConverter) round-trip + runReflexionLoop 循环行为(memory 累积 / 终止 /
 * max-reflections 兜底 / write 假完成守护)。mock 模式对齐 {@link PlanExecuteEngineTest}(11 bean +
 * 真实 {@link ExplicitToolExecutioner});循环行为测试用 Mockito spy 捕获 {@link ReflexionEngine#renderAttemptPrompt}
 * 的 memory 形参(证明前轮反思 lesson 已注入下一轮 Actor)。
 */
class ReflexionEngineTest {

    @Test
    void declaresReflexionParadigmAndName() {
        assertThat(newReflexionEngine().paradigm()).isEqualTo(AgentParadigm.REFLECTION);
        assertThat(newReflexionEngine().name()).isEqualTo("reflexion-loop");
    }

    @Test
    void supportsWhenParadigmIsReflection() {
        assertThat(newReflexionEngine().supports(ctxWithParadigm(AgentParadigm.REFLECTION))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        assertThat(newReflexionEngine().supports(ctxWithParadigm(AgentParadigm.REACT))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newReflexionEngine().supports(null)).isFalse();
    }

    // === RX-T2: renderAttemptPrompt + renderReflectionPrompt + ReflectionResult(BeanOutputConverter)===

    /**
     * renderAttemptPrompt 应注入 ReAct 风格协议(Thought/Action)、工具清单
     * ({@link ToolReflectionSupport#renderToolList})与前轮反思 memory。
     * 仿 {@link PlanExecuteEngineTest} 的 prompt 渲染断言风格。
     */
    @Test
    void rendersAttemptPromptWithMemoryAndTools() {
        ReflexionEngine engine = newReflexionEngine();
        Object[] tools = { new ReflexionToolFixture() };  // @Tool search(query)

        String prompt = engine.renderAttemptPrompt(reflexionCtx(), tools, "尝试1反思: 需更精确");

        assertThat(prompt).contains("Thought", "Action");          // 协议
        assertThat(prompt).contains("search");                     // 工具列表(ToolReflectionSupport.renderToolList)
        assertThat(prompt).contains("尝试1反思: 需更精确");          // memory 注入
    }

    /**
     * renderAttemptPrompt 在 memory 为空时给出"首次尝试"占位语(不出现 null/空段)。
     */
    @Test
    void rendersAttemptPromptWithEmptyMemoryShowsFirstAttemptHint() {
        ReflexionEngine engine = newReflexionEngine();
        Object[] tools = { new ReflexionToolFixture() };

        String prompt = engine.renderAttemptPrompt(reflexionCtx(), tools, "");

        assertThat(prompt).contains("首次尝试");
    }

    /**
     * renderReflectionPrompt 应注入本次尝试 + 历史 memory + BeanOutputConverter format
     * (强制 ReflectionResult 结构化输出,对齐 {@link PlanExecuteEngine#renderPlanPrompt} 的 format 注入)。
     */
    @Test
    void rendersReflectionPromptWithAttemptAndFormat() {
        ReflexionEngine engine = newReflexionEngine();

        String prompt = engine.renderReflectionPrompt(
                reflexionCtx(), "Thought: 调用 search\nAction: search(query=\"X\")", "尝试1反思: 需更精确");

        assertThat(prompt).contains("Thought: 调用 search");       // 本次尝试注入
        assertThat(prompt).contains("尝试1反思: 需更精确");          // 历史 memory 注入
        assertThat(prompt).contains("success").contains("critique").contains("lesson");  // ReflectionResult 字段(format 注入)
    }

    /**
     * 直连 BeanOutputConverter&lt;ReflectionResult&gt; 验证 record 的结构化解析
     * (self-review 关注点:boolean success + String critique/lesson 三字段能否 round-trip)。
     * 仿 {@link PlanExecuteEngineTest#beanOutputConverterParsesPlanFromJson}——不依赖任何 mock,
     * 直接证明 format 非空、convert(JSON) 能还原 ReflectionResult。
     */
    @Test
    void reflectionResultParsesFromJson() {
        BeanOutputConverter<ReflexionEngine.ReflectionResult> conv =
                new BeanOutputConverter<>(ReflexionEngine.ReflectionResult.class);

        assertThat(conv.getFormat()).isNotBlank();

        ReflexionEngine.ReflectionResult r = conv.convert(
                "{\"success\":true,\"critique\":\"尝试成功\",\"lesson\":\"保持策略\"}");

        assertThat(r.success()).isTrue();
        assertThat(r.critique()).contains("成功");
        assertThat(r.lesson()).contains("保持");
    }

    // === RX-T3: runReflexionLoop 循环(memory 累积 / 终止 / max-reflections / write 假完成守护)===

    /**
     * 主路径:尝试1 反思 success=false(lesson="需更精确地搜索")→ memory 累积 → 尝试2 反思 success=true
     * (read 任务)→ 收敛终止。断言:
     * <ul>
     *   <li>2 次尝试 + 2 次反思 = 各 2 次 executeChat(按 source 区分);</li>
     *   <li>第2次 {@link ReflexionEngine#renderAttemptPrompt} 的 memory 形参含第1次反思的 lesson(spy 捕获)——
     *       证明 Reflexion 的"反思→改进重试"闭环:Reflector lesson 已注入下一轮 Actor;</li>
     *   <li>第1次尝试 Action: search 经真实 ExplicitToolExecutioner 手动执行(searchCalls=1),第2次纯答案无 Action;</li>
     *   <li>终止:reflect 含成功尝试的答案文本。</li>
     * </ul>
     */
    @Test
    void reflexionLoopsUntilSuccessWithMemoryAccumulation() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReflexionToolFixture fixture = new ReflexionToolFixture();
        ReflexionEngine engine = newReflexionEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 3);
        ReflexionEngine spyEngine = spy(engine); // 捕获 renderAttemptPrompt 的 memory 形参

        AiProviderService.ActiveChatClient client = reflexionClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // 尝试 LLM 输出:第1次调 search 工具,第2次直接给最终答案(无 Action)
        String attempt1 = "Thought: 需要搜索 X\nAction: search(query=\"X\")";
        String attempt2 = "最终答案: X 是一个 AI 框架(已综合)。";
        doReturn(textCallResult(attempt1, client), textCallResult(attempt2, client))
                .when(executor).executeChat(any(), eq("reflexion-attempt"), any(), anyBoolean(), any());

        // 反思 LLM 输出:尝试1 success=false lesson;尝试2 success=true → 收敛
        ReflexionEngine.ReflectionResult r1 =
                new ReflexionEngine.ReflectionResult(false, "不够完整", "需更精确地搜索");
        ReflexionEngine.ReflectionResult r2 =
                new ReflexionEngine.ReflectionResult(true, "已完整回答", "保持策略");
        doReturn(reflectionCallResult(r1, client), reflectionCallResult(r2, client))
                .when(executor).executeChat(any(), eq("reflexion-reflect"), any(), anyBoolean(), any());

        ChatExecutionResult result = spyEngine.execute(
                reflexionCtx(client), (reason, ctx) -> "fallback");

        // 2 次尝试 + 2 次反思
        verify(executor, times(2)).executeChat(any(), eq("reflexion-attempt"), any(), anyBoolean(), any());
        verify(executor, times(2)).executeChat(any(), eq("reflexion-reflect"), any(), anyBoolean(), any());
        // 第2次 attempt prompt 的 memory 含第1次反思 lesson(Reflexion 自我纠错闭环核心证据)
        ArgumentCaptor<String> memoryCaptor = ArgumentCaptor.forClass(String.class);
        verify(spyEngine, times(2)).renderAttemptPrompt(any(), any(), memoryCaptor.capture());
        assertThat(memoryCaptor.getAllValues().get(1))
                .as("第2次尝试 prompt 的 memory 应含第1次反思的 lesson")
                .contains("需更精确地搜索");
        // 工具被手动执行恰好 1 次(第1次 attempt 的 Action,第2次纯答案无 Action)
        assertThat(fixture.searchCalls.get())
                .as("ExplicitToolExecutioner 应手动执行 search 恰好 1 次").isEqualTo(1);
        // 收敛终止:reflect 含成功尝试的答案
        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.reflect()).contains("X 是一个 AI 框架");
    }

    /**
     * 兜底路径:反思始终 success=false → 跑满 maxReflections=2 → 返回兜底结果。
     * 断言:2 次尝试 + 2 次反思 = 4 次 LLM 调用;reflect 含 "已达 max-reflections" 兜底提示。
     */
    @Test
    void reflexionStopsAtMaxReflections() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReflexionEngine engine = newReflexionEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2); // maxReflections=2

        AiProviderService.ActiveChatClient client = reflexionClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // 尝试 LLM 输出(纯答案,无工具)
        doReturn(textCallResult("最终答案: 仍在尝试。", client))
                .when(executor).executeChat(any(), eq("reflexion-attempt"), any(), anyBoolean(), any());
        // 反思始终 success=false
        ReflexionEngine.ReflectionResult notYet =
                new ReflexionEngine.ReflectionResult(false, "尚未达标", "继续改进");
        doReturn(reflectionCallResult(notYet, client))
                .when(executor).executeChat(any(), eq("reflexion-reflect"), any(), anyBoolean(), any());

        ChatExecutionResult result = engine.execute(
                reflexionCtx(client), (reason, ctx) -> "fallback");

        // 2 次尝试 + 2 次反思 = 4 次 LLM 调用
        verify(executor, times(2)).executeChat(any(), eq("reflexion-attempt"), any(), anyBoolean(), any());
        verify(executor, times(2)).executeChat(any(), eq("reflexion-reflect"), any(), anyBoolean(), any());
        assertThat(result.modelEnabled()).isTrue();
        assertThat(result.reflect()).contains("已达 max-reflections");
    }

    /**
     * 假完成守护:write 任务,反思 success=true 但 Actor 全程纯文本答案(无 Action / 无写工具)→
     * tracker 无写证据 → satisfiesCompletionCondition("write")=false → 拒绝收敛,继续循环 → 跑满
     * maxReflections=2 → 兜底。断言:reflect 含 "已达 max-reflections"(假答案未透传为终态)。
     * <p>对齐 {@link PlanExecuteEngineTest#writeTaskFakeCompletionGuardRejects} 的断言风格。</p>
     */
    @Test
    void writeTaskFakeCompletionGuardRejects() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        ReflexionEngine engine = newReflexionEngine(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 2); // maxReflections=2

        AiProviderService.ActiveChatClient client = reflexionClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // 尝试 LLM 输出:每次都纯文本"已创建文件"(无 Action → 无写工具证据)
        doReturn(textCallResult("最终答案: 我已经创建了文件 hello.txt。", client))
                .when(executor).executeChat(any(), eq("reflexion-attempt"), any(), anyBoolean(), any());
        // 反思始终 success=true(但 tracker 无写证据 → 假完成守护拦截)
        ReflexionEngine.ReflectionResult claimDone =
                new ReflexionEngine.ReflectionResult(true, "声称完成", "保持");
        doReturn(reflectionCallResult(claimDone, client))
                .when(executor).executeChat(any(), eq("reflexion-reflect"), any(), anyBoolean(), any());

        AgentDecision writeDecision = new AgentDecision(
                "general", "basic_model", List.of(), "write", false, "test write task");
        ChatExecutionResult result = engine.execute(
                reflexionCtx(client, writeDecision), (reason, ctx) -> "fallback");

        // 2 次尝试 + 2 次反思 = 4 次 LLM 调用(每次 success 都被假完成守护拦下,跑到 maxReflections)
        verify(executor, times(2)).executeChat(any(), eq("reflexion-attempt"), any(), anyBoolean(), any());
        verify(executor, times(2)).executeChat(any(), eq("reflexion-reflect"), any(), anyBoolean(), any());
        assertThat(result.modelEnabled()).isTrue();
        // reflect 主摘要为 max-reflections 兜底(非假答案)——假完成守护生效
        assertThat(result.reflect()).contains("已达 max-reflections");
    }

    /**
     * 构造 ReflexionEngine 骨架:mock 11 bean 依赖 + 真实 ExplicitToolExecutioner + maxReflections=3。
     * 依赖签名对齐 {@link PlanExecuteEngine}(RX-T1 接入 ExplicitToolExecutioner)。
     */
    private ReflexionEngine newReflexionEngine() {
        return new ReflexionEngine(
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
                new ExplicitToolExecutioner(),
                3
        );
    }

    /**
     * RX-T3 循环测试用:注入可 stub 的 ModelCallExecutor / ToolOrchestrator / Guard +
     * ExplicitToolExecutioner(真实)+ 自定义 maxReflections。仿 {@link PlanExecuteEngineTest} 同名工厂。
     */
    private ReflexionEngine newReflexionEngine(ModelCallExecutor executor,
                                               ToolOrchestrator toolOrchestrator,
                                               ModelTransportGuardService guard,
                                               ExplicitToolExecutioner toolExecutioner,
                                               int maxReflections) {
        return new ReflexionEngine(
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
                toolExecutioner,
                maxReflections
        );
    }

    /**
     * 构造可用的 ActiveChatClient(真实 record,无需 mock)。chatClient 置 null——
     * ModelCallExecutor 被 mock,真实调用 lambda 不会执行,无需 ChatClient。仿 PlanExecuteEngineTest。
     */
    private AiProviderService.ActiveChatClient reflexionClient() {
        return new AiProviderService.ActiveChatClient(
                "test", "test-model", "http://localhost", null, true, null);
    }

    /**
     * 构造 ModelCallResult&lt;String&gt;(value=Actor 每次尝试的 LLM 文本输出,client=failover 后客户端)。
     */
    private ModelCallExecutor.ModelCallResult<String> textCallResult(
            String text, AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(text, client, List.of(), false);
    }

    /**
     * 构造 ModelCallResult&lt;ReflectionResult&gt;(value=Reflector 结构化自评,client=failover 后客户端)。
     */
    private ModelCallExecutor.ModelCallResult<ReflexionEngine.ReflectionResult> reflectionCallResult(
            ReflexionEngine.ReflectionResult reflection, AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(reflection, client, List.of(), false);
    }

    /**
     * 构造 renderAttemptPrompt/renderReflectionPrompt 所需的最小 ChatContext——带 assembled(含 question)
     * + paradigm=REFLECTION。prompt 渲染读 {@link TypedContextPromptRenderer#question} / assembled,
     * 其余字段置 null(canonical 18 参构造,contextInjection null 由 compact ctor 兜底为 empty)。
     * 仿 {@link PlanExecuteEngineTest} 的 planExecuteCtx。
     */
    private ChatContext reflexionCtx() {
        return reflexionCtx(null, null);
    }

    /**
     * RX-T3 循环测试用:带 activeClient、decision=null(read 兜底)的便捷重载。
     */
    private ChatContext reflexionCtx(AiProviderService.ActiveChatClient client) {
        return reflexionCtx(client, null);
    }

    /**
     * RX-T3 循环测试用:带 activeClient + decision 的 reflexionCtx 重载。
     * decision=null → runReflexionLoop 兜底 riskLevel=read;write 假完成测试用 decision 注入 riskLevel=write。
     */
    private ChatContext reflexionCtx(AiProviderService.ActiveChatClient client, AgentDecision decision) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test-channel", "user-1",
                "请搜索 X 并综合给出回答",
                "", "", "# 当前问题\n请搜索 X 并综合给出回答");
        return new ChatContext(
                null, "test-channel", "user-1", null,
                "请搜索 X 并综合给出回答", "请搜索 X 并综合给出回答",
                "req-1", "SOUL", assembled, client,
                "reflexion", null, "agent", "general",
                decision, null, null, AgentParadigm.REFLECTION
        );
    }

    /**
     * 构造带 paradigm 的 ChatContext(canonical 18 参构造)。
     * supports() 只读 paradigm,其余字段置 null 即可。
     */
    private ChatContext ctxWithParadigm(AgentParadigm paradigm) {
        return new ChatContext(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, paradigm
        );
    }

    /**
     * 测试用工具 fixture:反射扫 {@link Tool} 的目标(模拟真实 tool pack bean)。
     * renderAttemptPrompt 经 {@link ToolReflectionSupport#renderToolList} 把它列成 "- search(query): ...";
     * RX-T3 循环测试里 Actor 的 Action: search(query=...) 经真实 ExplicitToolExecutioner 反射调用此方法,
     * searchCalls 计数器证明工具被**手动执行**(非 .tools() 内部往返)。
     */
    static class ReflexionToolFixture {
        final AtomicInteger searchCalls = new AtomicInteger();

        @Tool(name = "search", description = "搜索知识库")
        public String search(String query) {
            searchCalls.incrementAndGet();
            return "搜索结果:" + query;
        }
    }
}
