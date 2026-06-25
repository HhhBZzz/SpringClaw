package com.springclaw.service.session;

import com.baomidou.mybatisplus.extension.service.IService;
import com.springclaw.domain.entity.AgentSession;

/**
 * 会话服务接口。
 *
 * 设计说明：
 * 1. 使用 MyBatis-Plus IService 规范，符合你的技术栈硬约束。
 * 2. 保留扩展点，后续可以补充分库分表或冷热分层策略。
 */
public interface AgentSessionService extends IService<AgentSession> {

    AgentSession getOrCreate(String sessionKey, String channel, String userId);

    void persistConversation(AgentSession session, String userMessage, String assistantMessage, String soulVersion);

    void persistUserMessage(AgentSession session, String userMessage, String soulVersion);
}
