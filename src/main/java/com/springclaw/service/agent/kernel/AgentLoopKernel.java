package com.springclaw.service.agent.kernel;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享循环核:步进骨架 + step 边界事件(唯一发射点) + max-steps 兜底 + 假完成守护。
 * 引擎重叠段 A/C/D/G/H/K 的下沉目标(spec §3.2)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopKernel {

    private final ModelCallExecutor modelCallExecutor;
    private final RunLifecycleObserver lifecycleObserver;

    /** D 段:模型调用骨架(failover/事件富化已由 ModelCallExecutor 承担)。 */
    public <T> ModelCallExecutor.ModelCallResult<T> callModel(KernelCall<T> call)
            throws Exception {
        return modelCallExecutor.executeChat(
                call.activeClient(),
                call.source(),
                call.requestContext(),
                call.allowFailover(),
                call.operation()
        );
    }

    /**
     * 步进循环骨架。step 事件唯一发射点——引擎不再自己 beginStep。
     * <p>异常路径:nextStep/evaluate 抛出的 RuntimeException 先给当前 step 标记
     * failed outcome 再原样上抛(由引擎层 A 段壳捕获降级)。</p>
     */
    public <S, T> LoopOutcome<T> runLoop(LoopSpec<S, T> spec, LoopContext ctx) {
        ChatContext chat = ctx.chatContext();
        String requestId = chat == null ? null : chat.requestId();
        S state = spec.initState(chat);
        List<KernelResult<T>> steps = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < spec.maxSteps(); stepIndex++) {
            RunLifecycleObserver.StepScope scope =
                    lifecycleObserver.beginStep(requestId, stepIndex, spec.stepKind());
            try (scope) {
                state = spec.nextStep(ctx, state, stepIndex);
                KernelResult<T> stepResult = spec.evaluate(state, stepIndex);
                // G 段假完成守护:空产出即终止并标记
                if (stepResult.call() != null
                        && stepResult.call().value() instanceof String text
                        && !StringUtils.hasText(text)) {
                    if (scope != null) scope.outcome("empty_model_output");
                    steps.add(stepResult);
                    return new LoopOutcome<>(steps, true, "EMPTY_MODEL_OUTPUT", false, state);
                }
                steps.add(stepResult);
                if (stepResult.terminal()) {
                    if (scope != null) scope.outcome("terminal");
                    return new LoopOutcome<>(steps, false,
                            stepResult.terminalReason(), true, state);
                }
            } catch (RuntimeException ex) {
                if (scope != null) scope.outcome("failed");
                throw ex;
            }
        }
        // H 段 max-steps 兜底:非异常退出,按已达上限返回
        return new LoopOutcome<>(steps, false, "MAX_STEPS_REACHED", false, state);
    }

    /** N 段:终局答案组装(基于终局 state)。 */
    @SuppressWarnings("unchecked")
    public <S, T> String composeAnswer(LoopSpec<S, T> spec, ChatContext ctx,
                                       LoopOutcome<T> outcome) {
        return spec.composeAnswer(ctx, (S) outcome.finalState(), outcome.steps());
    }

    /**
     * 循环产出:逐步结果 + 终局方式。
     * finalState 为 spec 的终局状态(nextStep 最后演化值),供 composeAnswer 使用。
     */
    public record LoopOutcome<T>(List<KernelResult<T>> steps,
                                 boolean degraded,
                                 String endReason,
                                 boolean terminalBySpec,
                                 Object finalState) {
    }
}
