package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ChatContext;

/** 引擎侧上下文包:内核自身 + 引擎聊天上下文(由引擎构造)。 */
public record LoopContext(ChatContext chatContext, AgentLoopKernel kernel) {
}
