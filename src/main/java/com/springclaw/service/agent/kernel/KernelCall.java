package com.springclaw.service.agent.kernel;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;

/**
 * 一次内核模型调用的参数。operation 封装范式特定的 prompt 渲染与响应解析
 * (对应引擎重叠段 D/D' 的差异部分),骨架(failover/事件富化)由内核承担。
 */
public record KernelCall<T>(
        String source,
        AiProviderService.ActiveChatClient activeClient,
        ModelCallExecutor.ChatRequestContext requestContext,
        boolean allowFailover,
        ModelCallExecutor.ChatOperation<T> operation
) {
}
