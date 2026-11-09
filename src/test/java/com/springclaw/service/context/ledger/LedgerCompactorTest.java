package com.springclaw.service.context.ledger;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerCompactorTest {

    private static ChatContext contextWithLedger(RunMessageLedger ledger) throws Exception {
        var assembled = new com.springclaw.service.context.AssembledContext(
                "s1", "api", "u1", "问题", "", "", "observe", 0, 0);
        ChatContext ctx = new ChatContext(
                null, "api", "u1", "USER", "原始", "生效", "req-1", "SOUL",
                assembled, null, "agent", "t", "agent", "general",
                com.springclaw.service.agent.AgentDecision.general("t"),
                null, null, null, ledger
        );
        return ctx;
    }

    private static void appendBulk(RunMessageLedger ledger, int count, String filler) {
        // 构造器把预算钳制到最低 2000——填充须足够大让压力比确实越过阈值
        String bulk = "细节内容占位".repeat(60) + filler;
        for (int i = 0; i < count; i++) {
            ledger.appendUser("消息 " + i + " " + bulk);
            ledger.appendAssistant("回答 " + i + " " + bulk);
        }
    }

    @Test
    void noCompactionWhenUnderPressureOrTooFewMessages() throws Exception {
        LedgerCompactor compactor = new LedgerCompactor(mock(ModelCallExecutor.class),
                true, 100000, 0.8, 6);
        RunMessageLedger ledger = new RunMessageLedger("r", new com.springclaw.service.context.TokenMeter());
        appendBulk(ledger, 2, "x");

        assertThat(compactor.compactIfNeeded(contextWithLedger(ledger))).isFalse();
        assertThat(ledger.size()).isEqualTo(4);

        // 账本缺席
        assertThat(compactor.compactIfNeeded(contextWithLedger(null))).isFalse();
    }

    @Test
    void modelSummaryReplacesPrefixButKeepsRecentTail() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        when(executor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "任务目标:重构 X。已改 A.java。剩:验证。",
                        null, List.of(), false));
        // 预算压到很低强制触发
        LedgerCompactor compactor = new LedgerCompactor(executor, true, 10, 0.5, 4);

        RunMessageLedger ledger = new RunMessageLedger("r", new com.springclaw.service.context.TokenMeter());
        appendBulk(ledger, 8, "细节内容");

        int sizeBefore = ledger.size();
        boolean compacted = compactor.compactIfNeeded(contextWithLedger(ledger));

        assertThat(compacted).isTrue();
        // 1 条摘要 + 最近 4 条
        assertThat(ledger.size()).isEqualTo(5);
        assertThat(sizeBefore).isEqualTo(16);
        Message summary = ledger.view().get(0);
        assertThat(summary.getText()).contains("[上下文压缩]").contains("重构 X");
        // 尾部 4 条保留原文
        assertThat(ledger.view().get(4).getText()).contains("回答 7");
    }

    @Test
    void hardTruncateWhenSummaryCallFails() throws Exception {
        ModelCallExecutor executor = mock(ModelCallExecutor.class);
        when(executor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("模型不可用"));
        LedgerCompactor compactor = new LedgerCompactor(executor, true, 10, 0.5, 4);

        RunMessageLedger ledger = new RunMessageLedger("r", new com.springclaw.service.context.TokenMeter());
        appendBulk(ledger, 5, "内容");

        boolean compacted = compactor.compactIfNeeded(contextWithLedger(ledger));

        assertThat(compacted).isTrue();
        assertThat(ledger.size()).isEqualTo(5);
        String first = ledger.view().get(0).getText();
        assertThat(first).contains("[上下文压缩]").contains("截断");
        assertThat(ledger.view().get(4).getText()).contains("回答 4");
    }

    @Test
    void disabledCompactorIsNoOp() throws Exception {
        LedgerCompactor compactor = new LedgerCompactor(mock(ModelCallExecutor.class),
                false, 10, 0.5, 4);
        RunMessageLedger ledger = new RunMessageLedger("r", new com.springclaw.service.context.TokenMeter());
        appendBulk(ledger, 10, "内容");
        assertThat(compactor.compactIfNeeded(contextWithLedger(ledger))).isFalse();
        assertThat(ledger.size()).isEqualTo(20);
    }

    @Test
    void summaryPromptKeepsTaskArtifacts() {
        // 系统提示是压缩质量的关键锚点:任务目标/决定/文件/证据/进度五要素
        assertThat(LedgerCompactor.COMPACT_SYSTEM_PROMPT)
                .contains("任务目标")
                .contains("关键决定")
                .contains("文件")
                .contains("工具返回的关键证据")
                .contains("还剩什么没做");
    }
}
