package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * MultiAgentEngine 单测(MA-T1 骨架 + MA-T2 decompose/aggregate)。
 * mock 模式对齐 {@link ReflexionEngineTest}/{@link PlanExecuteEngineTest}(11 bean + 真实 {@link ExplicitToolExecutioner})。
 * <p>MA-T2 新增:decompose(BeanOutputConverter&lt;TaskDecomposition&gt; + .responseEntity)/
 * aggregate(LLM 综合,返回 String)/ TaskDecomposition round-trip 直连测试。
 * 循环体(runMultiAgentLoop 并行 + tracker 合并 + 假完成守护)由 MA-T3 填充。</p>
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
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test-channel", "user-1",
                "请搜索 X 并综合给出回答",
                "", "", "# 当前问题\n请搜索 X 并综合给出回答");
        return new ChatContext(
                null, "test-channel", "user-1", null,
                "请搜索 X 并综合给出回答", "请搜索 X 并综合给出回答",
                "req-1", "SOUL", assembled, client,
                "multi-agent", null, "agent", "general",
                null, null, null, AgentParadigm.MULTI_AGENT
        );
    }
}
