package com.springclaw.service.agent.kernel;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentLoopKernelTest {

    private final ModelCallExecutor executor = mock(ModelCallExecutor.class);
    private final RunLifecycleObserver observer = mock(RunLifecycleObserver.class);
    private final AgentLoopKernel kernel = new AgentLoopKernel(executor, observer);

    // 最小策略:state=List<String> 收集每步产出;evaluate 按 state 最后元素判 terminal
    private final LoopSpec<List<String>, String> spec = new LoopSpec<>() {
        @Override public int maxSteps() { return 3; }
        @Override public String stepKind() { return "test"; }
        @Override public List<String> initState(ChatContext ctx) { return new ArrayList<>(); }
        @Override public List<String> nextStep(LoopContext ctx, List<String> s, int i) {
            s.add("step-" + i);
            return s;
        }
        @Override public KernelResult<String> evaluate(List<String> s, int i) {
            return KernelResult.continuing(new ModelCallExecutor.ModelCallResult<>(
                    s.get(s.size() - 1), null, List.of(), false));
        }
        @Override public String composeAnswer(ChatContext ctx, List<String> s,
                                              List<KernelResult<String>> steps) {
            return String.join("|", s);
        }
    };

    private ChatContext chatContext() {
        // ChatContext 是 final record 不可 mock;18 参构造只填 requestId,其余置 null
        return new ChatContext(
                null, null, null, null,
                null, null, "rid-1", null,
                null, null, null, null, null, null,
                null, null, null, null
        );
    }

    @Test
    void stopsAtMaxStepsAndEmitsStepBoundaries() {
        AgentLoopKernel.LoopOutcome<String> outcome =
                kernel.runLoop(spec, new LoopContext(chatContext(), kernel));

        assertThat(outcome.endReason()).isEqualTo("MAX_STEPS_REACHED");
        assertThat(outcome.steps()).hasSize(3);
        assertThat(outcome.terminalBySpec()).isFalse();
        assertThat(outcome.finalState()).isEqualTo(List.of("step-0", "step-1", "step-2"));
        verify(observer, times(3)).beginStep(eq("rid-1"), anyInt(), eq("test"));
    }

    @Test
    void terminalBySpecStopsEarly() {
        LoopSpec<List<String>, String> terminalSpec = new LoopSpec<>() {
            @Override public int maxSteps() { return 5; }
            @Override public String stepKind() { return "test"; }
            @Override public List<String> initState(ChatContext ctx) { return new ArrayList<>(); }
            @Override public List<String> nextStep(LoopContext ctx, List<String> s, int i) {
                s.add("x");
                return s;
            }
            @Override public KernelResult<String> evaluate(List<String> s, int i) {
                return KernelResult.terminal(new ModelCallExecutor.ModelCallResult<>(
                        "final", null, List.of(), false), "NO_ACTION_LINE");
            }
            @Override public String composeAnswer(ChatContext ctx, List<String> s,
                                                  List<KernelResult<String>> steps) {
                return "final";
            }
        };

        AgentLoopKernel.LoopOutcome<String> outcome =
                kernel.runLoop(terminalSpec, new LoopContext(chatContext(), kernel));

        assertThat(outcome.terminalBySpec()).isTrue();
        assertThat(outcome.endReason()).isEqualTo("NO_ACTION_LINE");
        assertThat(outcome.steps()).hasSize(1);
    }

    @Test
    void emptyModelOutputTriggersFakeCompletionGuard() {
        LoopSpec<List<String>, String> blankSpec = new LoopSpec<>() {
            @Override public int maxSteps() { return 3; }
            @Override public String stepKind() { return "test"; }
            @Override public List<String> initState(ChatContext ctx) { return new ArrayList<>(); }
            @Override public List<String> nextStep(LoopContext ctx, List<String> s, int i) {
                s.add("");
                return s;
            }
            @Override public KernelResult<String> evaluate(List<String> s, int i) {
                return KernelResult.continuing(new ModelCallExecutor.ModelCallResult<>(
                        s.get(s.size() - 1), null, List.of(), false));
            }
            @Override public String composeAnswer(ChatContext ctx, List<String> s,
                                                  List<KernelResult<String>> steps) {
                return "never";
            }
        };

        AgentLoopKernel.LoopOutcome<String> outcome =
                kernel.runLoop(blankSpec, new LoopContext(chatContext(), kernel));

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.endReason()).isEqualTo("EMPTY_MODEL_OUTPUT");
        assertThat(outcome.steps()).hasSize(1);
    }

    @Test
    void stepFailurePropagates() {
        LoopSpec<List<String>, String> failing = new LoopSpec<>() {
            @Override public int maxSteps() { return 3; }
            @Override public String stepKind() { return "test"; }
            @Override public List<String> initState(ChatContext ctx) { return new ArrayList<>(); }
            @Override public List<String> nextStep(LoopContext ctx, List<String> s, int i) {
                throw new IllegalStateException("boom");
            }
            @Override public KernelResult<String> evaluate(List<String> s, int i) {
                return KernelResult.continuing(null);
            }
            @Override public String composeAnswer(ChatContext ctx, List<String> s,
                                                  List<KernelResult<String>> steps) {
                return "never";
            }
        };

        assertThatThrownBy(() -> kernel.runLoop(failing, new LoopContext(chatContext(), kernel)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }
}
