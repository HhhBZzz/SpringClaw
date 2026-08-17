package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ModelCallExecutor;

/** 内核单步产出:模型调用结果 + 是否判定为终局(假完成守护/范式终止条件)。 */
public record KernelResult<T>(
        ModelCallExecutor.ModelCallResult<T> call,
        boolean terminal,
        String terminalReason
) {
    public static <T> KernelResult<T> continuing(ModelCallExecutor.ModelCallResult<T> call) {
        return new KernelResult<>(call, false, "");
    }

    public static <T> KernelResult<T> terminal(
            ModelCallExecutor.ModelCallResult<T> call, String reason
    ) {
        return new KernelResult<>(call, true, reason);
    }
}
