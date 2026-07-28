package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ReflexionEngine 单测(RX-T1 骨架 + RX-T2 prompt/ReflectionResult)——验证 paradigm/name/supports 接入地基
 * + renderAttemptPrompt/renderReflectionPrompt 模板注入 + ReflectionResult(BeanOutputConverter) round-trip。
 * <p>循环体(Actor→Evaluator→Reflector,RX-T3)留待后续填充,此处只测骨架元数据 + 路由判定 + prompt 渲染。
 * mock 模式对齐 {@link PlanExecuteEngineTest}(11 bean + 真实 {@link ExplicitToolExecutioner})。</p>
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
        Object[] tools = { new FixtureTool() };  // @Tool search(query)

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
        Object[] tools = { new FixtureTool() };

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

    /**
     * 构造 ReflexionEngine 骨架:mock 11 bean 依赖 + 真实 ExplicitToolExecutioner + maxReflections=3。
     * 依赖签名对齐 {@link PlanExecute}(RX-T1 接入 ExplicitToolExecutioner)。
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
     * 构造 renderAttemptPrompt/renderReflectionPrompt 所需的最小 ChatContext——带 assembled(含 question)
     * + paradigm=REFLECTION。prompt 渲染读 {@link TypedContextPromptRenderer#question} / assembled,
     * 其余字段置 null(canonical 18 参构造,contextInjection null 由 compact ctor 兜底为 empty)。
     * 仿 {@link PlanExecuteEngineTest} 的 planExecuteCtx。
     */
    private ChatContext reflexionCtx() {
        AssembledContext assembled = new AssembledContext(
                "sess-1", "test-channel", "user-1",
                "请搜索 X 并综合给出回答",
                "", "", "# 当前问题\n请搜索 X 并综合给出回答");
        return new ChatContext(
                null, "test-channel", "user-1", null,
                "请搜索 X 并综合给出回答", "请搜索 X 并综合给出回答",
                "req-1", "SOUL", assembled, null,
                "reflexion", null, "agent", "general",
                null, null, null, AgentParadigm.REFLECTION
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
     * renderAttemptPrompt 经 {@link ToolReflectionSupport#renderToolList} 把它列成 "- search(query): ..."。
     */
    static class FixtureTool {
        @Tool(name = "search", description = "搜索知识库")
        public String search(String query) {
            return "搜索结果:" + query;
        }
    }
}
