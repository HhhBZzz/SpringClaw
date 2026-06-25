package com.springclaw.service.session;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.session.impl.AgentSessionServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A1 Task 5：AgentSessionService.persistUserMessage 更新用户消息与会话状态，
 * 但保留 lastAssistantMessage（挂起时不应覆盖已有助手回复）。
 */
class AgentSessionServiceImplTest {

    @Test
    void persistUserMessageUpdatesUserMessageAndStatusWithoutTouchingAssistant() {
        AgentSessionServiceImpl service = new AgentSessionServiceImpl(false, 5000, 24);
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        session.setChannel("api");
        session.setUserId("u1");
        session.setLastAssistantMessage("previous answer");
        session.setStatus("ACTIVE");

        service.persistUserMessage(session, "请确认这个动作", "v1");

        assertThat(session.getLastUserMessage()).isEqualTo("请确认这个动作");
        assertThat(session.getSoulVersion()).isEqualTo("v1");
        assertThat(session.getStatus()).isEqualTo("ACTIVE");
        assertThat(session.getLastAssistantMessage()).isEqualTo("previous answer");
    }

    @Test
    void persistUserMessageIsCachedLocallyWhenDbDisabled() {
        AgentSessionServiceImpl service = new AgentSessionServiceImpl(false, 5000, 24);
        AgentSession session = service.getOrCreate("s1", "api", "u1");

        service.persistUserMessage(session, "你好", "v1");

        AgentSession cached = service.getOrCreate("s1", "api", "u1");
        assertThat(cached.getLastUserMessage()).isEqualTo("你好");
        assertThat(cached.getLastAssistantMessage()).isNull();
    }
}
