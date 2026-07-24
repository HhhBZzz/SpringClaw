package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
