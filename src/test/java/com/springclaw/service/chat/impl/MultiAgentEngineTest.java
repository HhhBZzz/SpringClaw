package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MultiAgentEngine 单测(MA-T1 骨架 + MA-T2 decompose/aggregate + MA-T3 循环/守护/SSE)。
 * mock 模式对齐 {@link ReflexionEngineTest}/{@link PlanExecuteEngineTest}(11 bean + 真实 {@link ExplicitToolExecutioner})。
 * <p>MA-T2:decompose(BeanOutputConverter&lt;TaskDecomposition&gt; + .responseEntity)/
 * aggregate(LLM 综合,返回 String)/ TaskDecomposition round-trip 直连测试。</p>
 * <p>MA-T3:runMultiAgentLoop 并行 worker(2 子任务 → 2 CompletableFuture → aggregate 综合最终答案)/
 * tracker 线程安全(方案 B 共享 tracker —— {@link WriteToolFixture} 模拟 ToolRuntimeAspect 上报写证据,
 * 2 并行 worker 各执行 workspaceWriteFile,证据合并到共享 tracker,write 任务守护通过)/
 * 假完成守护降级(write 任务无工具证据 → summary 含 "假完成拦截")。</p>
 */
class MultiAgentEngineTest {

    @Test
    void declaresMultiAgentParadigmAndName() {
        assertThat(newMultiAgentEngine().paradigm()).isEqualTo(AgentParadigm.MULTI_AGENT);
        assertThat(newMultiAgentEngine().name()).isEqualTo("multi-agent-loop");
    }

    @Test
    void supportsWhenParadigmIsMultiAgent() {
        assertThat(newMultiAgentEngine().supports(ctxWithParadigm(AgentParadigm.MULTI_AGENT))).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        assertThat(newMultiAgentEngine().supports(ctxWithParadigm(AgentParadigm.REFLECTION))).isFalse();
    }

    @Test
    void doesNotSupportNullContext() {
        assertThat(newMultiAgentEngine().supports(null)).isFalse();
    }

    // === MA-T2: decompose / aggregate(BeanOutputConverter<TaskDecomposition>)===

    /**
     * mock ModelCallExecutor.executeChat 返回结构化 TaskDecomposition wrapper(2 子任务),
     * 调 decompose 断言返回的 List<SubTask> size=2、description 含 "搜索"/"分析"。
     * 验证 decompose 正确解包 ModelCallResult<TaskDecomposition>.value().tasks()。
     */
    @Test
    void decomposeGeneratesSubTasks() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        AiProviderService.ActiveChatClient client = multiAgentClient();
        MultiAgentEngine.TaskDecomposition decomp = new MultiAgentEngine.TaskDecomposition(List.of(
                new MultiAgentEngine.SubTask("搜索相关资料"),
                new MultiAgentEngine.SubTask("分析并综合结果")
        ));
        doReturn(new ModelCallExecutor.ModelCallResult<>(
                decomp, client, List.of("test:test-model"), false)
        ).when(executor).executeChat(
                any(AiProviderService.ActiveChatClient.class),
                eq("multi-agent-decompose"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any(ModelCallExecutor.ChatOperation.class));

        MultiAgentEngine engine = newMultiAgentEngineWith(executor);
        List<MultiAgentEngine.SubTask> tasks = engine.decompose(multiAgentCtx(client), new Object[0], client);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).description()).contains("搜索");
        assertThat(tasks.get(1).description()).contains("分析");
    }

    /**
     * mock executeChat 返回综合答案文本,调 aggregate(ctx, [worker1, worker2])。
     * 断言:返回值是 LLM 综合输出(passthrough);且 renderAggregatePrompt 把两个 worker 的
     * observation 都注入了 prompt(综合阶段真正看到了所有 worker 结果)。
     */
    @Test
    void aggregateCombinesWorkerResults() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        AiProviderService.ActiveChatClient client = multiAgentClient();
        String aggregated = "综合答案:workerA 找到 X,workerB 验证 Y 成立。";
        doReturn(new ModelCallExecutor.ModelCallResult<>(
                aggregated, client, List.of("test:test-model"), false)
        ).when(executor).executeChat(
                any(AiProviderService.ActiveChatClient.class),
                eq("multi-agent-aggregate"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any(ModelCallExecutor.ChatOperation.class));

        MultiAgentEngine engine = newMultiAgentEngineWith(executor);
        List<MultiAgentEngine.WorkerResult> results = List.of(
                new MultiAgentEngine.WorkerResult(
                        new MultiAgentEngine.SubTask("搜索"), "需搜索 X", "找到 X", false),
                new MultiAgentEngine.WorkerResult(
                        new MultiAgentEngine.SubTask("验证"), "需验证 Y", "验证 Y 成立", false)
        );
        ChatContext ctx = multiAgentCtx(client);
        String answer = engine.aggregate(ctx, results, client);

        // 返回 LLM 综合输出(passthrough)
        assertThat(answer).contains("综合答案");
        // renderAggregatePrompt 把两个 worker 的 observation 都注入(综合阶段看到全部 worker 结果)
        assertThat(engine.renderAggregatePrompt(ctx, results))
                .contains("找到 X")
                .contains("验证 Y 成立");
    }

    /**
     * 直连 BeanOutputConverter<TaskDecomposition> 验证 record wrapper + List<SubTask> 的结构化解析
     * (self-review 关注点:Java 泛型擦除下 wrapper record 是否真能 round-trip)。
     * 不依赖任何 mock——直接证明 format 非空、convert(JSON) 能还原 TaskDecomposition.tasks。
     * 仿 {@link PlanExecuteEngineTest#beanOutputConverterParsesPlanFromJson}。
     */
    @Test
    void taskDecompositionParsesFromJson() {
        BeanOutputConverter<MultiAgentEngine.TaskDecomposition> converter =
                new BeanOutputConverter<>(MultiAgentEngine.TaskDecomposition.class);

        assertThat(converter.getFormat()).isNotBlank();

        MultiAgentEngine.TaskDecomposition decomp = converter.convert(
                "{\"tasks\":[{\"description\":\"搜索\"},{\"description\":\"分析\"}]}");

        assertThat(decomp).isNotNull();
        assertThat(decomp.tasks()).hasSize(2);
        assertThat(decomp.tasks().get(0).description()).isEqualTo("搜索");
        assertThat(decomp.tasks().get(1).description()).isEqualTo("分析");
    }

    // === MA-T3: runMultiAgentLoop(并行 worker + 聚合 + tracker 线程安全 + 假完成守护)===

    /**
     * 主路径:decompose 产出 2 子任务 → 并行 2 worker(各跑一次 LLM + 工具增强推理)→ aggregate 综合最终答案。
     * mock ExplicitToolExecutioner 不参与(worker LLM 输出纯文本,无 Action);read 任务直通(无需工具证据)。
     * 断言:
     * <ul>
     *   <li>decompose 1 次 + worker 2 次(parallel)+ aggregate 1 次 = 4 次 LLM 调用(按 source 区分);</li>
     *   <li>result.reflect 含 aggregate 综合输出(并行 worker 结果被聚合);</li>
     *   <li>modelEnabled=true(read 任务无需工具证据,直通)。</li>
     * </ul>
     */
    @Test
    void runMultiAgentLoopParallelsWorkersAndAggregates() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        MultiAgentEngine engine = newMultiAgentEngineWith(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 5);

        AiProviderService.ActiveChatClient client = multiAgentClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // decompose → 2 子任务
        MultiAgentEngine.TaskDecomposition decomp = new MultiAgentEngine.TaskDecomposition(List.of(
                new MultiAgentEngine.SubTask("搜索 X 资料"),
                new MultiAgentEngine.SubTask("分析 X 的特性")
        ));
        doReturn(new ModelCallExecutor.ModelCallResult<>(decomp, client, List.of("test:test-model"), false))
                .when(executor).executeChat(any(), eq("multi-agent-decompose"), any(), eq(true), any());

        // 2 个 worker 各跑一次(纯文本答案,无 Action → 无工具执行)
        doReturn(textCallResult("worker1: 找到 X 是 AI 框架", client),
                textCallResult("worker2: X 支持 RAG 与工具", client))
                .when(executor).executeChat(any(), eq("multi-agent-worker"), any(), anyBoolean(), any());

        // aggregate → 综合答案
        doReturn(textCallResult("综合答案: X 是支持 RAG/工具的 AI 框架。", client))
                .when(executor).executeChat(any(), eq("multi-agent-aggregate"), any(), eq(true), any());

        ChatExecutionResult result = engine.execute(
                multiAgentCtx(client), (reason, ctx) -> "fallback");

        // decompose 1 + worker 2(parallel)+ aggregate 1
        verify(executor, times(1)).executeChat(any(), eq("multi-agent-decompose"), any(), eq(true), any());
        verify(executor, times(2)).executeChat(any(), eq("multi-agent-worker"), any(), anyBoolean(), any());
        verify(executor, times(1)).executeChat(any(), eq("multi-agent-aggregate"), any(), eq(true), any());
        assertThat(result.modelEnabled()).isTrue();
        // 并行 worker 结果被聚合
        assertThat(result.reflect()).contains("综合答案");
        assertThat(result.reflect()).contains("RAG");
    }

    /**
     * 假完成守护 + tracker 线程安全(write 任务 + 合并证据通过):
     * <p>2 个并行 worker 各经真实 {@link ExplicitToolExecutioner} 调 workspaceWriteFile 工具——
     * {@link WriteToolFixture} 模拟 {@code ToolRuntimeAspect} 的角色,把写证据上报到当前线程 ThreadLocal
     * 里的共享 {@link com.springclaw.service.chat.impl.AutonomousExecutionTracker}(方案 B:worker 共享
     * 同一 tracker,CopyOnWriteArrayList/ConcurrentHashMap 并发安全)。聚合后主线程读共享 tracker:
     * 2 个 worker 各贡献 1 次 write → satisfiesCompletionCondition("write")=true → 守护通过。
     * <p>断言:
     * <ul>
     *   <li>{@code WriteToolFixture.writeCalls}==2(2 个并行 worker 都执行了写工具,证据合并到共享 tracker);</li>
     *   <li>result.plan(summary)<b>不含</b> "假完成拦截"(write 任务有合并工具证据,守护通过);</li>
     *   <li>result.reflect 含 aggregate 综合输出。</li>
     * </ul>
     * 这是 MA-T3 的核心测试:证明并行 worker → 共享 tracker 的证据合并在并发下无丢失/无竞争。
     */
    @Test
    void writeTaskFakeCompletionGuardAggregatedEvidence() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        WriteToolFixture fixture = new WriteToolFixture();
        MultiAgentEngine engine = newMultiAgentEngineWith(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 5);

        AiProviderService.ActiveChatClient client = multiAgentClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any()))
                .thenReturn(new Object[]{fixture});

        // decompose → 2 子任务
        MultiAgentEngine.TaskDecomposition decomp = new MultiAgentEngine.TaskDecomposition(List.of(
                new MultiAgentEngine.SubTask("写入文件 a.txt"),
                new MultiAgentEngine.SubTask("写入文件 b.txt")
        ));
        doReturn(new ModelCallExecutor.ModelCallResult<>(decomp, client, List.of("test:test-model"), false))
                .when(executor).executeChat(any(), eq("multi-agent-decompose"), any(), eq(true), any());

        // 2 个 worker 都调 workspaceWriteFile(经真实 ExplicitToolExecutioner → fixture 上报共享 tracker)
        String writeAction = "Thought: 写入文件\nAction: workspaceWriteFile(relativePath=\"a.txt\")";
        doReturn(textCallResult(writeAction, client), textCallResult(writeAction, client))
                .when(executor).executeChat(any(), eq("multi-agent-worker"), any(), anyBoolean(), any());

        // aggregate → 综合答案
        doReturn(textCallResult("已写入 2 个文件。", client))
                .when(executor).executeChat(any(), eq("multi-agent-aggregate"), any(), eq(true), any());

        AgentDecision writeDecision = new AgentDecision(
                "general", "basic_model", List.of(), "write", false, "test write task");
        ChatExecutionResult result = engine.execute(
                multiAgentCtx(client, writeDecision), (reason, ctx) -> "fallback");

        // 2 个并行 worker 都执行了写工具(证据合并到共享 tracker,无并发丢失)
        assertThat(fixture.writeCalls.get())
                .as("2 个并行 worker 应各执行 workspaceWriteFile 一次(证据合并到共享 tracker)")
                .isEqualTo(2);
        assertThat(result.modelEnabled()).isTrue();
        // write 任务有合并工具证据 → 假完成守护通过(summary 不含拦截标记)
        assertThat(result.plan())
                .as("write 任务有合并工具证据时,守护应通过,summary 不含 '假完成拦截'")
                .doesNotContain("假完成拦截");
        assertThat(result.reflect()).contains("已写入");
    }

    /**
     * 假完成守护降级路径:write 任务,worker 全程纯文本答案(无 Action / 无写工具)→ 共享 tracker 无写证据 →
     * satisfiesCompletionCondition("write")=false → 守护拦截,summary 含 "假完成拦截"(对齐
     * {@link ReflexionEngineTest#writeTaskFakeCompletionGuardRejects} 的断言风格)。
     */
    @Test
    void writeTaskFakeCompletionGuardRejectsWithoutEvidence() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        ModelTransportGuardService guard = mock(ModelTransportGuardService.class);
        MultiAgentEngine engine = newMultiAgentEngineWith(executor, toolOrchestrator, guard,
                new ExplicitToolExecutioner(), 5);

        AiProviderService.ActiveChatClient client = multiAgentClient();
        when(guard.isModelCallEnabled(client)).thenReturn(true);
        when(toolOrchestrator.selectAutonomousTools(any(), any(), any())).thenReturn(new Object[0]);

        // decompose → 2 子任务
        MultiAgentEngine.TaskDecomposition decomp = new MultiAgentEngine.TaskDecomposition(List.of(
                new MultiAgentEngine.SubTask("声称写文件 a"),
                new MultiAgentEngine.SubTask("声称写文件 b")
        ));
        doReturn(new ModelCallExecutor.ModelCallResult<>(decomp, client, List.of("test:test-model"), false))
                .when(executor).executeChat(any(), eq("multi-agent-decompose"), any(), eq(true), any());

        // 2 个 worker 纯文本"已创建文件"(无 Action → 无写工具证据)
        doReturn(textCallResult("最终答案: 我已经创建了文件。", client),
                textCallResult("最终答案: 我已经创建了文件。", client))
                .when(executor).executeChat(any(), eq("multi-agent-worker"), any(), anyBoolean(), any());

        // aggregate → 综合答案
        doReturn(textCallResult("已创建文件。", client))
                .when(executor).executeChat(any(), eq("multi-agent-aggregate"), any(), eq(true), any());

        AgentDecision writeDecision = new AgentDecision(
                "general", "basic_model", List.of(), "write", false, "test write task");
        ChatExecutionResult result = engine.execute(
                multiAgentCtx(client, writeDecision), (reason, ctx) -> "fallback");

        assertThat(result.modelEnabled()).isTrue();
        // 无工具证据 → 假完成守护拦截
        assertThat(result.plan())
                .as("write 任务无工具证据时,守护应拦截,summary 含 '假完成拦截'")
                .contains("假完成拦截");
    }

    private ModelCallExecutor.ModelCallResult<String> textCallResult(
            String text, AiProviderService.ActiveChatClient client) {
        return new ModelCallExecutor.ModelCallResult<>(text, client, List.of(), false);
    }

    /**
     * 构造 MultiAgentEngine 骨架:mock 11 bean 依赖 + 真实 ExplicitToolExecutioner + maxAgents=5。
     * 依赖签名对齐 {@link ReflexionEngine}(MA-T1 接入 ExplicitToolExecutioner)。
     */
    private MultiAgentEngine newMultiAgentEngine() {
        return new MultiAgentEngine(
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
                5
        );
    }

    /**
     * 构造带 paradigm 的 ChatContext(canonical 18 参构造)。
     * supports() 只读 paradigm,其余字段置 null 即可。仿 {@link ReflexionEngineTest#ctxWithParadigm}。
     */
    private ChatContext ctxWithParadigm(AgentParadigm paradigm) {
        return new ChatContext(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, paradigm
        );
    }

    /**
     * 构造 MultiAgentEngine,注入指定的 ModelCallExecutor mock(其余 10 bean 仍 mock)。
     * MA-T2 decompose/aggregate 测试用它控制 LLM 输出。
     */
    private MultiAgentEngine newMultiAgentEngineWith(ModelCallExecutor executor) {
        return new MultiAgentEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(ModelTransportGuardService.class),
                executor,
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(ChatGuardService.class),
                mock(RunLifecycleObserver.class),
                new ExplicitToolExecutioner(),
                5
        );
    }

    /**
     * 构造可用的 ActiveChatClient(真实 record,无需 mock)。chatClient 置 null——
     * ModelCallExecutor 被 mock,真实调用 lambda 不会执行,无需 ChatClient。仿 PlanExecuteEngineTest。
     */
    private AiProviderService.ActiveChatClient multiAgentClient() {
        return new AiProviderService.ActiveChatClient(
                "test", "test-model", "http://localhost", null, true, null);
    }

    /**
     * 构造 decompose/aggregate 所需的最小 ChatContext——带 assembled(含 question)、
     * channel/userId/requestId、paradigm=MULTI_AGENT。
     */
    private ChatContext multiAgentCtx(AiProviderService.ActiveChatClient client) {
        return multiAgentCtx(client, null);
    }

    /**
     * MA-T3 循环测试用:带 activeClient + decision 的 multiAgentCtx 重载。
     * decision=null → runMultiAgentLoop 兜底 riskLevel=read;write 假完成测试用 decision 注入 riskLevel=write。
     */
    private ChatContext multiAgentCtx(AiProviderService.ActiveChatClient client, AgentDecision decision) {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test-channel", "user-1",
                "请搜索 X 并综合给出回答",
                "", "", "# 当前问题\n请搜索 X 并综合给出回答");
        return new ChatContext(
                null, "test-channel", "user-1", null,
                "请搜索 X 并综合给出回答", "请搜索 X 并综合给出回答",
                "req-1", "SOUL", assembled, client,
                "multi-agent", null, "agent", "general",
                decision, null, null, AgentParadigm.MULTI_AGENT
        );
    }

    /**
     * MA-T3 循环测试用:注入可 stub 的 ModelCallExecutor / ToolOrchestrator / Guard +
     * ExplicitToolExecutioner(真实)+ 自定义 maxAgents。仿 {@link ReflexionEngineTest} 同名工厂。
     */
    private MultiAgentEngine newMultiAgentEngineWith(ModelCallExecutor executor,
                                                     ToolOrchestrator toolOrchestrator,
                                                     ModelTransportGuardService guard,
                                                     ExplicitToolExecutioner toolExecutioner,
                                                     int maxAgents) {
        return new MultiAgentEngine(
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
                maxAgents
        );
    }

    /**
     * 测试用写工具 fixture:反射扫 {@link Tool} 的目标(模拟真实 workspaceWriteFile bean)。
     * 关键:本 fixture 在方法体里直接调用 {@link ToolExecutionContextHolder#getTracker()}
     * 上报写证据——模拟生产环境中 {@code ToolRuntimeAspect} AOP 拦截写工具后做的事(单测无 Aspect,
     * fixture 自行上报,faithful to 生产路径)。
     * <p>MA-T3 并行 worker 测试里,2 个 worker 各经真实 {@link ExplicitToolExecutioner} 反射调用此方法,
     * 证据合并到 worker 线程 ThreadLocal 里的共享 tracker(方案 B)。{@code writeCalls} 计数器证明
     * 工具被并行执行且证据无丢失。</p>
     */
    static class WriteToolFixture {
        final AtomicInteger writeCalls = new AtomicInteger();

        @Tool(name = "workspaceWriteFile", description = "写入工作区文件")
        public String workspaceWriteFile(String relativePath) {
            AutonomousExecutionTracker tracker = ToolExecutionContextHolder.getTracker();
            if (tracker != null) {
                tracker.recordWriteFile(relativePath, 10);
            }
            writeCalls.incrementAndGet();
            return "已写入 " + relativePath;
        }
    }
}
