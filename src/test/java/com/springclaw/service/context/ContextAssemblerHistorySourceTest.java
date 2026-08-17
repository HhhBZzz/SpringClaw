package com.springclaw.service.context;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.history.ConversationHistoryDeriver;
import com.springclaw.runtime.history.ConversationTurn;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryBankService;
import com.springclaw.service.memory.MemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 历史源切换测试(spec 2026-08-17-canonical-conversation-history §3.3):
 * 开关 canonical → 派生器路径;派生异常 → 回退 message_event;开关 message-event → 原路径。
 */
class ContextAssemblerHistorySourceTest {

    private final MessageEventService messageEventService = mock(MessageEventService.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final ConversationHistoryDeriver deriver = mock(ConversationHistoryDeriver.class);

    private ContextAssembler assembler(String source) {
        return new ContextAssembler(
                messageEventService, memoryService, new MemoryBankService(false, "", 400),
                8, 8, 400, deriver, source);
    }

    @Test
    void canonicalSourceRendersTurnsFromDeriver() {
        when(deriver.derive("s1", 16)).thenReturn(List.of(
                new ConversationTurn(ConversationTurn.Role.USER, "canonical 问题", "r1",
                        Instant.parse("2026-08-17T00:00:03Z"), ConversationTurn.Source.CANONICAL),
                new ConversationTurn(ConversationTurn.Role.ASSISTANT, "canonical 回答", "r1",
                        Instant.parse("2026-08-17T00:00:07Z"), ConversationTurn.Source.CANONICAL)
        ));
        when(memoryService.recallBySession(anyString(), anyString(), anyInt())).thenReturn(List.of());

        AssembledContext context = assembler("canonical").assemble("s1", "api", "u1", "下一问");

        assertThat(context.eventContext())
                .contains("- USER: canonical 问题")
                .contains("- ASSISTANT: canonical 回答");
        // canonical 源不读 message_event
        verify(messageEventService, never()).listRecent(anyString(), anyInt());
    }

    @Test
    void canonicalSourceFallsBackToMessageEventsWhenDeriverThrows() {
        when(deriver.derive(anyString(), anyInt())).thenThrow(new IllegalStateException("store down"));
        when(messageEventService.listRecent("s1", 16)).thenReturn(List.of(
                event("USER", "CHAT", "[OBSERVE] legacy 问题"),
                event("ASSISTANT", "CHAT", "[REFLECT] legacy 回答")
        ));
        when(memoryService.recallBySession(anyString(), anyString(), anyInt())).thenReturn(List.of());

        AssembledContext context = assembler("canonical").assemble("s1", "api", "u1", "下一问");

        // 回退后 LLM 历史仍可用(不 fail 请求)
        assertThat(context.eventContext()).contains("legacy 问题");
        verify(messageEventService).listRecent("s1", 16);
    }

    @Test
    void canonicalSourceFallsBackWhenDeriverReturnsEmpty() {
        // 新会话/旧数据:派生为空 → 回退 legacy(双源拼接过渡的最小形态:
        // 派生器空说明 canonical 无该会话记录,legacy 有就用)
        when(deriver.derive("s1", 16)).thenReturn(List.of());
        when(messageEventService.listRecent("s1", 16)).thenReturn(List.of(
                event("USER", "CHAT", "[OBSERVE] legacy 问题")
        ));
        when(memoryService.recallBySession(anyString(), anyString(), anyInt())).thenReturn(List.of());

        AssembledContext context = assembler("canonical").assemble("s1", "api", "u1", "下一问");

        assertThat(context.eventContext()).contains("legacy 问题");
    }

    @Test
    void messageEventSourceKeepsLegacyPathUntouched() {
        when(messageEventService.listRecent("s1", 16)).thenReturn(List.of(
                event("USER", "CHAT", "[OBSERVE] legacy 问题")
        ));
        when(memoryService.recallBySession(anyString(), anyString(), anyInt())).thenReturn(List.of());

        AssembledContext context = assembler("message-event").assemble("s1", "api", "u1", "下一问");

        assertThat(context.eventContext()).contains("legacy 问题");
        verify(deriver, never()).derive(anyString(), anyInt());
    }

    @Test
    void bothSourcesEmptyRendersPlaceholder() {
        when(deriver.derive("s1", 16)).thenReturn(List.of());
        when(messageEventService.listRecent("s1", 16)).thenReturn(List.of());
        when(memoryService.recallBySession(anyString(), anyString(), anyInt())).thenReturn(List.of());

        AssembledContext context = assembler("canonical").assemble("s1", "api", "u1", "第一问");

        assertThat(context.eventContext()).isEqualTo("（暂无短期事件流）");
    }

    private static MessageEvent event(String role, String eventType, String content) {
        MessageEvent e = new MessageEvent();
        e.setSessionKey("s1");
        e.setChannel("api");
        e.setUserId("u1");
        e.setRole(role);
        e.setEventType(eventType);
        e.setContent(content);
        return e;
    }
}
