package com.springclaw.service.chat.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AgentContextMetricsService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseEventBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void shouldExposeContextSummaryInMetaEvent() throws Exception {
        SseEventBridge bridge = new SseEventBridge(null);
        CapturingEmitter emitter = new CapturingEmitter();
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "问题",
                "- USER: 历史问题",
                "- [SESSION] ASSISTANT: 长期记忆",
                """
                        # 当前问题
                        问题

                        # 项目记忆（Memory Bank）
                        ### current-state
                        停止合并 engine，优先稳定 harness。

                        # 短期会话上下文（事件流）
                        - USER: 历史问题

                        # 长期语义记忆（同会话优先）
                        - [SESSION] ASSISTANT: 长期记忆
                        """,
                2,
                1
        );
        ChatContext context = chatContext("agent", "simplified", "general", AgentDecision.general("普通聊天"), assembled);

        bridge.sendMeta(emitter, context);

        Map<String, Object> meta = readMetaPayload(emitter);
        Map<String, Object> summary = asMap(meta.get("contextSummary"));

        Assertions.assertEquals("springclaw.context-source.v1", summary.get("schema"));
        Assertions.assertEquals(true, summary.get("memoryBankUsed"));
        Assertions.assertEquals(12, summary.get("shortTermChars"));
        Assertions.assertEquals(2, summary.get("memoryLearningActiveCount"));
        Assertions.assertEquals(1, summary.get("memoryLearningFilteredCount"));
        Assertions.assertFalse(emitter.payloads.get(0).contains("停止合并 engine，优先稳定 harness。"));
    }

    @Test
    void shouldRecordContextSummaryMetricsWhenMetaEventIsSent() {
        AgentContextMetricsService metricsService = mock(AgentContextMetricsService.class);
        SseEventBridge bridge = new SseEventBridge(null, metricsService);
        CapturingEmitter emitter = new CapturingEmitter();
        ChatContext context = chatContext("agent", "simplified", "general", AgentDecision.general("普通聊天"));

        bridge.sendMeta(emitter, context);

        verify(metricsService).record(any(AssembledContext.ContextSourceSummary.class));
    }

    @Test
    void shouldExposeEmptyContextSummaryWhenAssembledContextIsMissing() throws Exception {
        SseEventBridge bridge = new SseEventBridge(null);
        CapturingEmitter emitter = new CapturingEmitter();
        ChatContext context = chatContext("agent", "simplified", "general", AgentDecision.general("普通聊天"), null);

        bridge.sendMeta(emitter, context);

        Map<String, Object> summary = asMap(readMetaPayload(emitter).get("contextSummary"));

        Assertions.assertEquals("springclaw.context-source.v1", summary.get("schema"));
        Assertions.assertEquals(false, summary.get("memoryBankUsed"));
        Assertions.assertEquals(0, summary.get("memoryBankChars"));
        Assertions.assertEquals(0, summary.get("shortTermChars"));
        Assertions.assertEquals(0, summary.get("semanticMemoryChars"));
        Assertions.assertEquals(0, summary.get("observePromptChars"));
        Assertions.assertEquals(0, summary.get("memoryLearningActiveCount"));
        Assertions.assertEquals(0, summary.get("memoryLearningFilteredCount"));
    }

    private ChatContext chatContext(String responseMode,
                                    String executionMode,
                                    String intent,
                                    AgentDecision decision) {
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "问题",
                "- USER: 问题",
                "（暂无长期语义记忆）",
                "# 当前问题\n问题"
        );
        return chatContext(responseMode, executionMode, intent, decision, assembled);
    }

    private ChatContext chatContext(String responseMode,
                                    String executionMode,
                                    String intent,
                                    AgentDecision decision,
                                    AssembledContext assembled) {
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
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

    private Map<String, Object> readMetaPayload(CapturingEmitter emitter) throws Exception {
        Assertions.assertFalse(emitter.payloads.isEmpty());
        String raw = emitter.payloads.stream()
                .filter(payload -> payload.contains("{"))
                .findFirst()
                .orElseThrow();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        Assertions.assertTrue(start >= 0 && end > start);
        return objectMapper.readValue(raw.substring(start, end + 1), new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        Assertions.assertTrue(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
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
