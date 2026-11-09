package com.springclaw.service.context.ledger;

import com.springclaw.service.context.TokenMeter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunMessageLedgerTest {

    private final TokenMeter meter = new TokenMeter();

    @Test
    void appendUserAndAssistantPreserveVerbatimText() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendUser("列出超过 500 行的文件");
        ledger.appendAssistant("| a.java |\n| b.java |");

        List<Message> view = ledger.view();
        assertThat(view).hasSize(2);
        assertThat(view.get(0)).isInstanceOf(UserMessage.class);
        assertThat(view.get(0).getText()).isEqualTo("列出超过 500 行的文件");
        assertThat(view.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(view.get(1).getText()).isEqualTo("| a.java |\n| b.java |");
    }

    @Test
    void appendAssistantOutputFabricatesProtocolValidToolRound() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendUser("任务");
        ledger.appendAssistantOutput(
                List.of(
                        new ToolRecord("readFile", "[\"a.java\"]", "content-of-a"),
                        new ToolRecord("runCmd", "[\"ls\"]", "ok")
                ),
                "执行完成"
        );

        List<Message> view = ledger.view();
        // user + assistant(toolCalls) + tool(responses) + assistant(text)
        assertThat(view).hasSize(4);
        AssistantMessage toolCallMessage = (AssistantMessage) view.get(1);
        assertThat(toolCallMessage.getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(toolCallMessage.hasToolCalls()).isTrue();
        assertThat(toolCallMessage.getToolCalls()).hasSize(2);
        assertThat(toolCallMessage.getToolCalls().get(0).name()).isEqualTo("readFile");
        assertThat(toolCallMessage.getToolCalls().get(0).type()).isEqualTo("function");

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) view.get(2);
        assertThat(toolResponseMessage.getMessageType()).isEqualTo(MessageType.TOOL);
        assertThat(toolResponseMessage.getResponses()).hasSize(2);

        // id 配对:toolCalls 与 responses 一一对应
        for (int i = 0; i < 2; i++) {
            assertThat(toolResponseMessage.getResponses().get(i).id())
                    .isEqualTo(toolCallMessage.getToolCalls().get(i).id());
        }
        assertThat(toolResponseMessage.getResponses().get(0).name()).isEqualTo("readFile");
        assertThat(toolResponseMessage.getResponses().get(0).responseData()).isEqualTo("content-of-a");

        assertThat(view.get(3).getText()).isEqualTo("执行完成");
        // callId 唯一
        assertThat(toolCallMessage.getToolCalls().get(0).id())
                .isNotEqualTo(toolCallMessage.getToolCalls().get(1).id());
    }

    @Test
    void appendAssistantOutputWithoutRecordsEqualsAppendAssistant() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendAssistantOutput(List.of(), "纯文本步");
        assertThat(ledger.view()).hasSize(1);
        assertThat(ledger.view().get(0).getText()).isEqualTo("纯文本步");
    }

    @Test
    void blankAppendsAreIgnored() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendUser("");
        ledger.appendAssistant(null);
        assertThat(ledger.view()).isEmpty();
        assertThat(ledger.size()).isZero();
    }

    @Test
    void viewIsImmutableSnapshot() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendUser("a");
        List<Message> view = ledger.view();
        assertThatThrownBy(() -> view.add(new UserMessage("b")))
                .isInstanceOf(UnsupportedOperationException.class);
        ledger.appendUser("c");
        assertThat(ledger.view()).hasSize(2);
        assertThat(view).hasSize(1);
    }

    @Test
    void estimateTokensGrowsWithAppendsAndCoversToolArgs() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        int before = ledger.estimateTokens();
        ledger.appendUser("hello world this is a token estimate test");
        int afterUser = ledger.estimateTokens();
        assertThat(afterUser).isGreaterThan(before);

        int beforeRound = ledger.estimateTokens();
        ledger.appendAssistantOutput(
                List.of(new ToolRecord("readFile", "[" + "x".repeat(400) + "]", "y".repeat(400))),
                "done"
        );
        assertThat(ledger.estimateTokens()).isGreaterThan(beforeRound);

        assertThat(ledger.pressure(1)).isGreaterThan(1.0);
        assertThat(ledger.pressure(0)).isZero();
    }

    @Test
    void replaceRangeSwapsSegmentForSummaryMessage() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendUser("q1");
        ledger.appendAssistant("a1");
        ledger.appendUser("q2");
        ledger.appendAssistant("a2");

        ledger.replaceRange(0, 2, new UserMessage("[摘要] q1/a1 已压缩"));

        List<Message> view = ledger.view();
        assertThat(view).hasSize(3);
        assertThat(view.get(0).getText()).isEqualTo("[摘要] q1/a1 已压缩");
        assertThat(view.get(1).getText()).isEqualTo("q2");
        assertThat(view.get(2).getText()).isEqualTo("a2");
    }

    @Test
    void replaceRangeWithNullReplacementOnlyDrops() {
        RunMessageLedger ledger = new RunMessageLedger("run-1", meter);
        ledger.appendUser("q1");
        ledger.appendAssistant("a1");
        ledger.appendUser("q2");
        ledger.replaceRange(0, 2, null);
        assertThat(ledger.view()).hasSize(1);
        assertThat(ledger.view().get(0).getText()).isEqualTo("q2");
    }
}
