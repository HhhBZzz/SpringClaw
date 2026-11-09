package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.TokenMeter;
import com.springclaw.service.context.ledger.RunMessageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerPromptSupportTest {

    private ChatClient.ChatClientRequestSpec mockSpec() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.messages(any(Message[].class))).thenReturn(spec);
        return spec;
    }

    private ChatContext contextWithLedger(RunMessageLedger ledger) {
        AssembledContext assembled = new AssembledContext(
                "s1", "api", "u1", "生效问题", "", "", "observe", 0, 0);
        return new ChatContext(
                new AgentSession(), "api", "u1", "USER",
                "原始问题", "生效问题", "req-1", "SOUL",
                assembled, null, "agent", "test",
                "agent", "general",
                com.springclaw.service.agent.AgentDecision.general("测试"),
                null, null, AgentParadigm.SINGLE_TURN,
                ledger
        );
    }

    @Test
    void nativePathSendsLedgerMessagesInsteadOfRenderedText() {
        LedgerPromptSupport support = new LedgerPromptSupport(true);
        RunMessageLedger ledger = new RunMessageLedger("req-1", new TokenMeter());
        ledger.appendUser("历史问题");
        ledger.appendAssistant("历史回答");
        ledger.appendUser("当前问题");
        ChatContext ctx = contextWithLedger(ledger);

        ChatClient.ChatClientRequestSpec spec = mockSpec();
        support.applyPrompt(spec, ctx, "legacy 渲染文本");

        verify(spec).system("SOUL");
        verify(spec).messages(ledger.view());
        verify(spec, never()).user(anyString());
    }

    @Test
    void legacyPathUsedWhenFlagDisabled() {
        LedgerPromptSupport support = new LedgerPromptSupport(false);
        RunMessageLedger ledger = new RunMessageLedger("req-1", new TokenMeter());
        ledger.appendUser("当前问题");
        ChatContext ctx = contextWithLedger(ledger);

        ChatClient.ChatClientRequestSpec spec = mockSpec();
        support.applyPrompt(spec, ctx, "legacy 渲染文本");

        verify(spec).system("SOUL");
        verify(spec).user("legacy 渲染文本");
        verify(spec, never()).messages(anyList());
    }

    @Test
    void legacyPathUsedWhenLedgerAbsentOrEmpty() {
        LedgerPromptSupport support = new LedgerPromptSupport(true);

        // 账本缺席(装配失败回退)
        ChatClient.ChatClientRequestSpec specAbsent = mockSpec();
        support.applyPrompt(specAbsent, contextWithLedger(null), "legacy 文本");
        verify(specAbsent).user("legacy 文本");

        // 账本为空(无种子无 briefing)不发送空 messages
        ChatClient.ChatClientRequestSpec specEmpty = mockSpec();
        support.applyPrompt(specEmpty, contextWithLedger(new RunMessageLedger("r", new TokenMeter())), "legacy 文本");
        verify(specEmpty).user("legacy 文本");
    }

    @Test
    void initRunAppendsBriefingWithQuestion() {
        LedgerPromptSupport support = new LedgerPromptSupport(true);
        RunMessageLedger ledger = new RunMessageLedger("req-1", new TokenMeter());
        ledger.appendUser("历史问题");
        ChatContext ctx = contextWithLedger(ledger);

        support.initRun(ctx, "你是 ReAct 引擎,按 Thought/Action 输出");

        List<Message> view = ledger.view();
        assertThat(view).hasSize(2);
        String briefing = view.get(1).getText();
        assertThat(briefing)
                .contains("你是 ReAct 引擎")
                .contains("# 任务")
                .contains("生效问题");
    }

    @Test
    void initRunWithoutBriefingAppendsQuestionOnly() {
        LedgerPromptSupport support = new LedgerPromptSupport(true);
        RunMessageLedger ledger = new RunMessageLedger("req-1", new TokenMeter());
        ChatContext ctx = contextWithLedger(ledger);

        support.initRun(ctx, "");
        assertThat(ledger.view()).hasSize(1);
        assertThat(((UserMessage) ledger.view().get(0)).getText()).isEqualTo("生效问题");
    }

    @Test
    void initRunNoOpOnLegacyPath() {
        LedgerPromptSupport support = new LedgerPromptSupport(false);
        RunMessageLedger ledger = new RunMessageLedger("req-1", new TokenMeter());
        support.initRun(contextWithLedger(ledger), "briefing");
        assertThat(ledger.view()).isEmpty();
        assertThat(support.nativeAvailable(contextWithLedger(ledger))).isFalse();
    }
}
