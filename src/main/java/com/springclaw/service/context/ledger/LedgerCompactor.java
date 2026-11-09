package com.springclaw.service.context.ledger;

import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.context.AgentContextMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * 账本压缩器(spec 2026-11-09 §3.6):run 内 pre-step 检查,压力比超阈值时
 * 把"除最近 keepRecent 条之外"的账本段替换为模型摘要(MODEL_SUMMARY);
 * 摘要调用失败降级硬截断(HARD_TRUNCATE,带丢弃规模标记)。
 *
 * <p>触发点在 AgentLoopKernel 步进前——所有 kernel 引擎单点受益。
 * 压缩是 observation(CONTEXT_COMPACTED),不改 run 状态机。</p>
 */
@Component
public class LedgerCompactor {

    private static final Logger log = LoggerFactory.getLogger(LedgerCompactor.class);

    /** 摘要转换单条消息文本上限(转写总规模有界)。 */
    private static final int TRANSCRIPT_ITEM_CHARS = 600;

    static final String COMPACT_SYSTEM_PROMPT = """
            你是对话压缩器。把以下消息历史压缩成一份结构化摘要,供同一个 Agent 继续任务时使用。
            必须保留:任务目标与约束、已做出的关键决定、已读/已改的文件与位置、工具返回的关键证据、
            当前进行到哪一步、还剩什么没做。丢弃寒暄与重复。中文输出,不超过 600 字。""";

    private final ModelCallExecutor modelCallExecutor;
    private final ObjectProvider<RunCoordinator> runCoordinatorProvider;
    private final AgentContextMetricsService metricsService;
    private final boolean enabled;
    private final int budgetTokens;
    private final double triggerRatio;
    private final int keepRecentMessages;

    @Autowired
    public LedgerCompactor(ModelCallExecutor modelCallExecutor,
                           ObjectProvider<RunCoordinator> runCoordinatorProvider,
                           @Autowired(required = false) AgentContextMetricsService metricsService,
                           @Value("${springclaw.context.compaction-enabled:true}") boolean enabled,
                           @Value("${springclaw.context.token-budget-tokens:24000}") int budgetTokens,
                           @Value("${springclaw.context.compaction-trigger-ratio:0.8}") double triggerRatio,
                           @Value("${springclaw.context.compaction-keep-recent-messages:6}") int keepRecentMessages) {
        this.modelCallExecutor = modelCallExecutor;
        this.runCoordinatorProvider = runCoordinatorProvider;
        this.metricsService = metricsService;
        this.enabled = enabled;
        this.budgetTokens = Math.max(2000, budgetTokens);
        this.triggerRatio = Math.min(0.99, Math.max(0.3, triggerRatio));
        this.keepRecentMessages = Math.max(2, keepRecentMessages);
    }

    /** 测试兼容构造(无事件/指标)。 */
    public LedgerCompactor(ModelCallExecutor modelCallExecutor,
                           boolean enabled,
                           int budgetTokens,
                           double triggerRatio,
                           int keepRecentMessages) {
        this(modelCallExecutor, null, null, enabled, budgetTokens, triggerRatio, keepRecentMessages);
    }

    /**
     * 压力超阈值时压缩。返回 true 表示发生了压缩(引擎可据此记 trace);
     * 账本缺席/未启用/压力未达/条数不足一律 false,绝不抛异常阻塞步进。
     */
    public boolean compactIfNeeded(ChatContext ctx) {
        if (!enabled || ctx == null || ctx.messageLedger() == null) {
            return false;
        }
        RunMessageLedger ledger = ctx.messageLedger();
        int size = ledger.size();
        if (size <= keepRecentMessages) {
            return false;
        }
        if (ledger.pressure(budgetTokens) <= triggerRatio) {
            return false;
        }
        int to = size - keepRecentMessages;
        int beforeTokens = ledger.estimateTokens();
        List<Message> dropped = ledger.view().subList(0, to);
        String transcript = renderTranscript(dropped);
        try {
            String summary = summarize(ctx, transcript);
            if (!StringUtils.hasText(summary)) {
                throw new IllegalStateException("压缩摘要为空");
            }
            ledger.replaceRange(0, to, new UserMessage(
                    "[上下文压缩] 以下为本次任务此前历史的摘要(原文已压缩):\n" + summary));
            emit(ctx, "MODEL_SUMMARY", beforeTokens, ledger.estimateTokens(), to);
            return true;
        } catch (Exception ex) {
            log.warn("账本模型压缩失败,降级硬截断: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
            ledger.replaceRange(0, to, new UserMessage(
                    "[上下文压缩] 此前 " + to + " 条消息因超出 token 预算被截断;任务目标与约束以 briefing 为准,已丢失细节不再可查。"));
            emit(ctx, "HARD_TRUNCATE", beforeTokens, ledger.estimateTokens(), to);
            return true;
        }
    }

    private String summarize(ChatContext ctx, String transcript) throws Exception {
        ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                ctx.activeClient(),
                "context-compaction",
                new ModelCallExecutor.ChatRequestContext(
                        ctx.requestId(),
                        ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                        ctx.channel(),
                        ctx.userId()
                ),
                true,
                client -> {
                    var response = client.chatClient().prompt()
                            .system(COMPACT_SYSTEM_PROMPT)
                            .user(transcript)
                            .call()
                            .chatResponse();
                    return new ModelCallExecutor.ChatOperationResult<>(
                            ModelCallExecutor.extractText(response), response);
                }
        );
        return result.value();
    }

    /** 摘要转写:逐条截断拼装(压缩调用的输入自身有界)。 */
    private static String renderTranscript(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            String role = message.getMessageType().name();
            String text = message.getText() == null ? "" : message.getText();
            if (text.length() > TRANSCRIPT_ITEM_CHARS) {
                text = text.substring(0, TRANSCRIPT_ITEM_CHARS) + "...";
            }
            builder.append('[').append(role).append("] ").append(text).append('\n');
        }
        return builder.toString();
    }

    private void emit(ChatContext ctx, String method, int before, int after, int dropped) {
        RunCoordinator coordinator = runCoordinatorProvider == null
                ? null : runCoordinatorProvider.getIfAvailable();
        if (coordinator != null && StringUtils.hasText(ctx.requestId())) {
            try {
                coordinator.contextCompacted(ctx.requestId(), method,
                        before, after, dropped, Instant.now());
            } catch (RuntimeException ignored) {
                // emit 失败不影响步进
            }
        }
        if (metricsService != null) {
            metricsService.recordLedgerCompaction(method, dropped, before, after);
        }
    }
}
