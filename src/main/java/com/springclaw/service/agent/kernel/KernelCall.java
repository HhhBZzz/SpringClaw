package com.springclaw.service.agent.kernel;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;

/**
 * 一次内核模型调用的参数。operation 封装范式特定的 prompt 渲染与响应解析
 * (对应引擎重叠段 D/D' 的差异部分),骨架(failover/事件富化)由内核承担。
 *
 * @param source        调用源标签(事件富化用,如 "react-step-2"/"plan"/"action")
 * @param activeClient  本步起始 provider 客户端(failover 后经 ModelCallResult.client 更新回 state)
 * @param requestContext 请求上下文(requestId/sessionKey/channel/userId,富化事件)
 * @param allowFailover 是否允许换 provider 重试——写类工具集应传
 *                      {@link ModelCallSafety#isSafeToRetry(Object[])} 的 false
 *                      (防副作用重放)
 * @param operation     范式特定的调用操作(prompt 渲染 + 响应解析)
 * @param <T>           模型产出类型(String 文本 / 结构化 Plan 等)
 */
public record KernelCall<T>(
        String source,
        AiProviderService.ActiveChatClient activeClient,
        ModelCallExecutor.ChatRequestContext requestContext,
        boolean allowFailover,
        ModelCallExecutor.ChatOperation<T> operation
) {
}
