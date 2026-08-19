package com.springclaw.service.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具结果免模型剪枝器(dsh compaction-tool-result-pruner 的 SpringClaw 对应物)。
 *
 * <p>问题:工具输出全文进入模型上下文(展示层截 220 字符只影响 renderEventLine,
 * 模型看到的是全文)——大输出(编译日志/文件内容/搜索命中)是上下文爆炸主因。</p>
 *
 * <p>策略:超阈值(thresholdChars)的输出保留头 headChars + 尾 tailChars,
 * 中段替换为 PRUNE_MARKER(标注剪掉规模)。纯字符操作,零模型调用;
 * 保留头尾对日志/代码/列表类输出保真(错误在头,结论在尾)。</p>
 */
@Component
public class ToolResultPruner {

    public static final int DEFAULT_THRESHOLD_CHARS = 6000;
    public static final int DEFAULT_HEAD_CHARS = 1500;
    public static final int DEFAULT_TAIL_CHARS = 1500;
    public static final String PRUNE_MARKER_TEMPLATE =
            "\n...<pruned %d characters: tool output exceeded %d chars; head %d + tail %d retained>\n";
    /** marker 固定片段(测试锚点)。 */
    public static final String PRUNE_MARKER = "<pruned";

    private final int thresholdChars;
    private final int headChars;
    private final int tailChars;

    public ToolResultPruner() {
        this(DEFAULT_THRESHOLD_CHARS, DEFAULT_HEAD_CHARS, DEFAULT_TAIL_CHARS);
    }

    public ToolResultPruner(
            @Value("${springclaw.tools.result-prune-threshold-chars:6000}") int thresholdChars,
            @Value("${springclaw.tools.result-prune-head-chars:1500}") int headChars,
            @Value("${springclaw.tools.result-prune-tail-chars:1500}") int tailChars) {
        this.thresholdChars = Math.max(1000, thresholdChars);
        this.headChars = Math.max(0, Math.min(headChars, this.thresholdChars / 2));
        this.tailChars = Math.max(0, Math.min(tailChars, this.thresholdChars / 2));
    }

    /** 超预算则剪枝(头+marker+尾);预算内原样返回;null 透传。 */
    public String prune(String output) {
        if (output == null || output.length() <= thresholdChars) {
            return output;
        }
        String head = output.substring(0, headChars);
        String tail = output.substring(output.length() - tailChars);
        int prunedCount = output.length() - headChars - tailChars;
        return head + PRUNE_MARKER_TEMPLATE.formatted(prunedCount, thresholdChars, headChars, tailChars) + tail;
    }

    /** 便捷常量暴露(测试/诊断)。 */
    public int threshold() {
        return thresholdChars;
    }
}
