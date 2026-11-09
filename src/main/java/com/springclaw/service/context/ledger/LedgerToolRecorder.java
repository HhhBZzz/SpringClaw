package com.springclaw.service.context.ledger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 步内工具执行记录器(spec 2026-11-09 §3.4)。
 *
 * <p>ToolRuntimeAspect(AOP,所有 @Tool 执行的唯一路径)在每次工具执行后
 * {@link #record};引擎在步末拿到模型文本时 {@link #drain} 取走并清空,
 * 随 {@code RunMessageLedger.appendAssistantOutput} 伪造为协议合法的
 * tool_use/tool_result 消息对。异步流式路径(reactor 线程执行工具)读不到
 * ThreadLocal 上下文时自然不记录——账本退化为纯文本历史,不报错。</p>
 */
public final class LedgerToolRecorder {

    /** 单条 args JSON 的账本截断上限(结果侧已由 ToolResultPruner 承担)。 */
    static final int MAX_ARGS_CHARS = 1200;

    /** 非 String 结果的账本截断上限(与 ToolResultPruner 阈值一致)。 */
    static final int MAX_RESULT_CHARS = 6000;

    /** 单步最大记录条数(防工具风暴撑爆账本;超出丢弃并标记)。 */
    static final int MAX_RECORDS_PER_STEP = 32;

    private final List<ToolRecord> records = new CopyOnWriteArrayList<>();
    private volatile boolean overflowed;

    /** 记录一次工具执行;超上限丢弃(overflowed 标记让步末补一条说明)。 */
    public void record(String name, String argsJson, String resultText) {
        if (records.size() >= MAX_RECORDS_PER_STEP) {
            overflowed = true;
            return;
        }
        records.add(new ToolRecord(
                safe(name),
                truncate(safe(argsJson), MAX_ARGS_CHARS),
                truncate(safe(resultText), MAX_RESULT_CHARS)
        ));
    }

    /** 取走全部记录并清空(步末一次性消费)。 */
    public List<ToolRecord> drain() {
        List<ToolRecord> drained = List.copyOf(records);
        records.clear();
        return drained;
    }

    /** 是否发生过上限丢弃(取走后仍可查询,供步末补记说明)。 */
    public boolean isOverflowed() {
        return overflowed;
    }

    /** 重置溢出标记(新一步开始时由引擎调用)。 */
    public void resetOverflow() {
        overflowed = false;
    }

    /** 步内是否有记录。 */
    public boolean isEmpty() {
        return records.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...<truncated>";
    }
}
