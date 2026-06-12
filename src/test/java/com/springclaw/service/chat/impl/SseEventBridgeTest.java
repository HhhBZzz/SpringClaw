package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseEventBridgeTest {

    @Test
    void shouldIncludeProductModeInMetaEventAndRunMetadata() {
        AgentRunTraceService traceService = mock(AgentRunTraceService.class);
        SseEventBridge bridge = new SseEventBridge(traceService);
        CapturingEmitter emitter = new CapturingEmitter();
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                java.util.List.of("workspace-edit"),
                "write",
                true,
                "写入需要确认"
        );
        ChatContext context = chatContext("agent", "opar", "workspace_analysis", decision);

        bridge.sendMeta(emitter, context);

        Assertions.assertTrue(emitter.payloads.stream()
                .anyMatch(payload -> payload.contains("\"productMode\":\"execution_task\"")));
        verify(traceService).recordRunMetadata(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("req-1"),
                eq("agent"),
                eq("opar"),
                eq("workspace_analysis"),
                eq("execution_task")
        );
    }

    private ChatContext chatContext(String responseMode,
                                    String executionMode,
                                    String intent,
                                    AgentDecision decision) {
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "问题",
                "- USER: 问题",
                "（暂无长期语义记忆）",
                "# 当前问题\n问题"
        );
        AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );
        return new ChatContext(
                session,
                "api",
                "u1",
                "USER",
                "问题",
                "问题",
                "req-1",
                "system",
                assembled,
                activeClient,
                executionMode,
                "测试路由",
                responseMode,
                intent,
                decision
        );
    }

    private static class CapturingEmitter extends SseEmitter {
        private final List<String> payloads = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (item.getData() != null) {
                    payloads.add(String.valueOf(item.getData()));
                }
            }
        }
    }
}
