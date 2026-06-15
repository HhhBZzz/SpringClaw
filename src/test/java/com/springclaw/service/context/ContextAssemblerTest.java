package com.springclaw.service.context;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryBankService;
import com.springclaw.service.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldNormalizeLegacyObservedChatEventsWhenBuildingEventContext() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(messageEventService.listRecent("s1", 16)).thenReturn(List.of(
                event("USER", "CHAT", """
                        [OBSERVE] # 当前问题
                        第一轮问题

                        # 短期会话上下文（事件流）
                        - SYSTEM: ...
                        """),
                event("ASSISTANT", "CHAT", "[REFLECT] 第一轮回答"),
                event("SYSTEM", "OPAR", "PLAN=[STEP 1] READY")
        ));
        when(memoryService.recallBySession("s1", "现在继续", 8)).thenReturn(List.of());
        when(memoryService.recallByUser("u1", "现在继续", 4)).thenReturn(List.of());

        ContextAssembler assembler = new ContextAssembler(messageEventService, memoryService, 8, 8, 400);

        AssembledContext context = assembler.assemble("s1", "api", "u1", "现在继续");

        assertThat(context.eventContext())
                .contains("- USER: 第一轮问题")
                .contains("- ASSISTANT: 第一轮回答")
                .contains("- SYSTEM: PLAN=[STEP 1] READY")
                .doesNotContain("# 短期会话上下文（事件流）");
    }

    @Test
    void shouldKeepGroupSharedHistoryButSkipCrossSessionUserRecallForFeishuGroup() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(messageEventService.listRecent("feishu:group:oc_demo_group", 16)).thenReturn(List.of(
                event("USER", "CHAT", "今天先接 DeepSeek", "feishu", "feishu:group:oc_demo_group", "ou_a"),
                event("USER", "CHAT", "然后确认 Redis 记忆", "feishu", "feishu:group:oc_demo_group", "ou_b")
        ));
        when(memoryService.recallBySession("feishu:group:oc_demo_group", "总结一下今天聊了什么", 8)).thenReturn(List.of(
                new Document(
                        "d1",
                        "今天先接 DeepSeek",
                        Map.of(
                                "channel", "feishu",
                                "sessionKey", "feishu:group:oc_demo_group",
                                "userId", "ou_a",
                                "role", "USER"
                        )
                )
        ));

        ContextAssembler assembler = new ContextAssembler(messageEventService, memoryService, 8, 8, 400);

        AssembledContext context = assembler.assemble(
                "feishu:group:oc_demo_group",
                "feishu",
                "ou_b",
                "总结一下今天聊了什么"
        );

        assertThat(context.eventContext())
                .contains("USER(ou_a): 今天先接 DeepSeek")
                .contains("USER(ou_b): 然后确认 Redis 记忆");
        assertThat(context.semanticContext())
                .contains("[SESSION] USER(ou_a): 今天先接 DeepSeek")
                .doesNotContain("[USER]");
        verify(memoryService, never()).recallByUser("ou_b", "总结一下今天聊了什么", 4);
    }

    @Test
    void shouldIncludeProjectMemoryBankInObservePrompt() throws Exception {
        MessageEventService messageEventService = mock(MessageEventService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(messageEventService.listRecent("s1", 16)).thenReturn(List.of());
        when(memoryService.recallBySession("s1", "当前项目怎么推进", 8)).thenReturn(List.of());
        when(memoryService.recallByUser("u1", "当前项目怎么推进", 4)).thenReturn(List.of());
        Files.writeString(tempDir.resolve("current-state.md"), "# Current State\n\n停止合并 engine，优先稳定 harness。");
        MemoryBankService memoryBankService = new MemoryBankService(true, tempDir.toString(), 800);

        ContextAssembler assembler = new ContextAssembler(
                messageEventService,
                memoryService,
                memoryBankService,
                8,
                8,
                400
        );

        AssembledContext context = assembler.assemble("s1", "api", "u1", "当前项目怎么推进");

        assertThat(context.observePrompt())
                .contains("# 项目记忆（Memory Bank）")
                .contains("### current-state")
                .contains("停止合并 engine，优先稳定 harness。");
    }

    private MessageEvent event(String role, String eventType, String content) {
        return event(role, eventType, content, "api", "s1", "u1");
    }

    private MessageEvent event(String role,
                               String eventType,
                               String content,
                               String channel,
                               String sessionKey,
                               String userId) {
        MessageEvent event = new MessageEvent();
        event.setRole(role);
        event.setEventType(eventType);
        event.setContent(content);
        event.setChannel(channel);
        event.setSessionKey(sessionKey);
        event.setUserId(userId);
        return event;
    }
}
