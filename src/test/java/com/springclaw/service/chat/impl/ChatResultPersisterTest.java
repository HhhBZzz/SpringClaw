package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.event.MessageEventReceipt;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.event.MessageEventWrite;
import com.springclaw.service.memory.extraction.MemoryExtractionTrigger;
import com.springclaw.service.memory.ShortTermMemoryWriter;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3A1 Task 5：ChatResultPersister 按 intent 区分终端写与挂起写。
 *
 * 守住不变量：
 *   - TERMINAL_RESULT：写 user/assistant 消息、事件流和 Redis short-term shadow；
 *   - CONFIRMATION_SUSPENSION：只写 user 消息 + chat:&lt;runId&gt;:user 事件，
 *     不写终端 assistant 事件。
 */
class ChatResultPersisterTest {

    private final AgentSessionService agentSessionService = mock(AgentSessionService.class);
    private final MessageEventService messageEventService = mock(MessageEventService.class);
    private final SoulPromptService soulPromptService = mock(SoulPromptService.class);
    private final ShortTermMemoryWriter shortTermMemoryWriter =
            mock(ShortTermMemoryWriter.class);
    private final MemoryExtractionTrigger memoryExtractionTrigger =
            mock(MemoryExtractionTrigger.class);

    private ChatResultPersister persister() {
        when(soulPromptService.soulVersion()).thenReturn("v1");
        return new ChatResultPersister(
                agentSessionService, messageEventService, soulPromptService,
                shortTermMemoryWriter);
    }

    private ChatResultPersister persisterWithExtractionTrigger() {
        when(soulPromptService.soulVersion()).thenReturn("v1");
        return new ChatResultPersister(
                agentSessionService, messageEventService, soulPromptService,
                shortTermMemoryWriter, memoryExtractionTrigger);
    }

    private static ChatContext context() {
        AgentSession session = new AgentSession();
        session.setSessionKey("s1");
        session.setUserId("u1");
        session.setChannel("api");
        return new ChatContext(
                session, "api", "u1", "USER", "你好", "你好", "req-1", "system",
                null, null, "simplified", "默认", "agent", "general", null, null);
    }

    @Test
    void terminalResultDoesNotWriteDirectSemanticTurnMemory() {
        ChatResultPersister persister = persister();
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "PLAN", "ACT", "answer", true);

        persister.persist(context, "answer", result, ChatPersistenceIntent.TERMINAL_RESULT);

        verify(agentSessionService).persistConversation(
                eq(context.session()), eq("你好"), eq("answer"), eq("v1"));
    }

    @Test
    void terminalResultShadowWritesUserAndAssistantWithStableReceipts() {
        ChatResultPersister persister = persister();
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "PLAN", "ACT", "answer", true);
        MessageEventReceipt userReceipt = new MessageEventReceipt(
                10L, "chat:req-1:user", Instant.parse("2026-06-23T00:00:00Z"));
        MessageEventReceipt assistantReceipt = new MessageEventReceipt(
                11L, "chat:req-1:assistant:terminal", Instant.parse("2026-06-23T00:00:01Z"));
        when(messageEventService.append(argThat((MessageEventWrite write) ->
                write != null && write.eventKey().endsWith(":user")))).thenReturn(userReceipt);
        when(messageEventService.append(argThat((MessageEventWrite write) ->
                write != null && write.eventKey().endsWith(":assistant:terminal")))).thenReturn(assistantReceipt);

        persister.persist(context, "answer", result, ChatPersistenceIntent.TERMINAL_RESULT);

        verify(shortTermMemoryWriter).appendTerminal(
                context, userReceipt, "你好", assistantReceipt, "answer");
    }

    @Test
    void terminalResultDoesNotFailWhenShadowWriteFails() {
        ChatResultPersister persister = persister();
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "PLAN", "ACT", "answer", true);
        MessageEventReceipt userReceipt = new MessageEventReceipt(
                10L, "chat:req-1:user", Instant.parse("2026-06-23T00:00:00Z"));
        MessageEventReceipt assistantReceipt = new MessageEventReceipt(
                11L, "chat:req-1:assistant:terminal", Instant.parse("2026-06-23T00:00:01Z"));
        when(messageEventService.append(argThat((MessageEventWrite write) ->
                write != null && write.eventKey().endsWith(":user")))).thenReturn(userReceipt);
        when(messageEventService.append(argThat((MessageEventWrite write) ->
                write != null && write.eventKey().endsWith(":assistant:terminal")))).thenReturn(assistantReceipt);
        doThrow(new IllegalStateException("redis down"))
                .when(shortTermMemoryWriter)
                .appendTerminal(context, userReceipt, "你好", assistantReceipt, "answer");

        persister.persist(context, "answer", result, ChatPersistenceIntent.TERMINAL_RESULT);

        verify(agentSessionService).persistConversation(
                eq(context.session()), eq("你好"), eq("answer"), eq("v1"));
    }

    @Test
    void terminalResultQueuesMemoryExtractionAfterTerminalEventsArePersisted() {
        ChatResultPersister persister = persisterWithExtractionTrigger();
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "PLAN", "ACT", "answer", true);

        persister.persist(context, "answer", result, ChatPersistenceIntent.TERMINAL_RESULT);

        verify(memoryExtractionTrigger).afterTerminalPersistence(
                context.requestId(),
                context.userId()
        );
    }

    @Test
    void confirmationSuspensionDoesNotWriteAssistantSemanticMemory() {
        ChatResultPersister persister = persister();
        ChatContext context = context();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe", "ACTION_REQUIRED", "reason", "请确认", false);

        persister.persist(
                context, "请确认", result, ChatPersistenceIntent.CONFIRMATION_SUSPENSION);

        verify(agentSessionService).persistUserMessage(
                eq(context.session()), eq(context.effectiveUserMessage()), anyString());
        verify(messageEventService).append(argThat((MessageEventWrite write) ->
                write.eventKey().equals("chat:" + context.requestId() + ":user")));
        verify(messageEventService).append(argThat((MessageEventWrite write) ->
                write.eventKey().equals("chat:" + context.requestId() + ":suspension")));
        verify(memoryExtractionTrigger, never()).afterTerminalPersistence(anyString(), anyString());
    }
}
