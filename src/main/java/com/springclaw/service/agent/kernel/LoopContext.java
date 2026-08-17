package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ChatContext;

/**
 * 引擎侧上下文包:内核自身 + 引擎聊天上下文(由引擎构造)。
 * spec 的 nextStep 经 kernel() 发起模型调用,chatContext() 供取
 * requestId/channel/userId 等(可为 null——PlanExecute 外层循环用
 * AssembledContext 构造时)。
 */
public record LoopContext(ChatContext chatContext, AgentLoopKernel kernel) {
}
