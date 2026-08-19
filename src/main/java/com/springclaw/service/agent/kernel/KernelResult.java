package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ModelCallExecutor;

/** 内核单步产出:模型调用结果 + 是否判定为终局(假完成守护/范式终止条件)。 */
public record KernelResult<T>(
        ModelCallExecutor.ModelCallResult<T> call,
        boolean terminal,
        String terminalReason
) {
    /**
     * 继续循环。call 可为 null(引擎侧自行管理步产出的场景,如
     * AutonomousLoop/OparLoop 把产出存在 state 里)。
     */
    public static <T> KernelResult<T> continuing(ModelCallExecutor.ModelCallResult<T> call) {
        return new KernelResult<>(call, false, "");
    }

    /**
     * 终止循环。reason 进 LoopOutcome.endReason(如 "FINAL_ANSWER"/
     * "TASK_COMPLETE_VERIFIED"/"PLAN_READY"/"DEGRADED"/"REFLECTED_ENOUGH")。
     */
    public static <T> KernelResult<T> terminal(
            ModelCallExecutor.ModelCallResult<T> call, String reason
    ) {
        return new KernelResult<>(call, true, reason);
    }
}
