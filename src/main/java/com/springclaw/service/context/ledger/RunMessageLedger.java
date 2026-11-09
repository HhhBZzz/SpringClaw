package com.springclaw.service.context.ledger;

import com.springclaw.service.context.TokenMeter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生消息账本(spec 2026-11-09 §3.1):run 内存态的模型可见上下文,只追加不重写。
 *
 * <p>消息序契约: system(SOUL) → 种子历史 → [记忆块] → briefing+问题 → run 内追加。
 * 前缀(system+历史)跨调用稳定,天然适配前缀缓存;查询相关内容只出现在尾部。</p>
 *
 * <p>持久化仍走 canonical 事件日志——账本是派生视图,不是第二事实源。
 * 工具轮以协议合法顺序伪造:
 * AssistantMessage(toolCalls) → ToolResponseMessage(responses) → AssistantMessage(文本)。</p>
 */
public final class RunMessageLedger {

    private final String runId;
    private final TokenMeter tokenMeter;
    private final List<Message> messages = new ArrayList<>();
    private final AtomicInteger callSeq = new AtomicInteger();
    private volatile int cachedTokens;
    private boolean runInitialized;

    public RunMessageLedger(String runId, TokenMeter tokenMeter) {
        this.runId = runId == null ? "" : runId;
        this.tokenMeter = tokenMeter == null ? new TokenMeter() : tokenMeter;
    }

    /** run 任务消息是否已初始化(briefing+问题)。 */
    public synchronized boolean isRunInitialized() {
        return runInitialized;
    }

    /** 标记初始化;重复调用返回 false(幂等,fallback 重入防重复 briefing)。 */
    public synchronized boolean markRunInitialized() {
        if (runInitialized) {
            return false;
        }
        runInitialized = true;
        return true;
    }

    public String runId() {
        return runId;
    }

    /** 追加用户消息(当前问题/briefing/步间提示注入)。 */
    public synchronized void appendUser(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        messages.add(new UserMessage(text));
        invalidate();
    }

    /** 追加助手纯文本输出(无工具轮的步,如 Plan 调用)。 */
    public synchronized void appendAssistant(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        messages.add(new AssistantMessage(text));
        invalidate();
    }

    /**
     * 步末入账:本步工具记录伪造为 tool_use/tool_result 消息对,后接助手终文本。
     * records 为空时等价于 {@link #appendAssistant}。
     */
    public synchronized void appendAssistantOutput(List<ToolRecord> records, String assistantText) {
        if (records != null && !records.isEmpty()) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>(records.size());
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(records.size());
            for (ToolRecord record : records) {
                String id = nextCallId();
                calls.add(new AssistantMessage.ToolCall(id, "function", record.name(), record.argsJson()));
                responses.add(new ToolResponseMessage.ToolResponse(id, record.name(), record.resultText()));
            }
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(calls)
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(responses)
                    .metadata(Map.of())
                    .build());
        }
        if (assistantText != null && !assistantText.isBlank()) {
            messages.add(new AssistantMessage(assistantText));
        }
        invalidate();
    }

    /**
     * 压缩替换:把 [from, to) 区间替换为单条消息(摘要/截断标记)。
     * 越界区间按 List.subList 语义抛异常——压缩器负责区间合法性。
     */
    public synchronized void replaceRange(int from, int to, Message replacement) {
        messages.subList(from, to).clear();
        if (replacement != null) {
            messages.add(from, replacement);
        }
        invalidate();
    }

    /** 不可变快照(发给模型的就是这份)。 */
    public synchronized List<Message> view() {
        return List.copyOf(messages);
    }

    public synchronized int size() {
        return messages.size();
    }

    /** 全账本 token 估算(变更时失效重算;ToolCall 参数计入)。 */
    public synchronized int estimateTokens() {
        return cachedTokens;
    }

    /** 占预算比例(0..1+);预算非正返回 0。 */
    public synchronized double pressure(int budgetTokens) {
        if (budgetTokens <= 0) {
            return 0;
        }
        return (double) cachedTokens / budgetTokens;
    }

    private void invalidate() {
        int total = 0;
        for (Message message : messages) {
            total += tokenMeter.estimate(message.getText());
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    total += tokenMeter.estimate(call.name()) + tokenMeter.estimate(call.arguments());
                }
            }
        }
        cachedTokens = total;
    }

    private String nextCallId() {
        return "call_" + runId + "_" + callSeq.incrementAndGet();
    }
}
