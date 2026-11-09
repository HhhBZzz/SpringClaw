package com.springclaw.tool.runtime;

import com.springclaw.service.chat.impl.AutonomousExecutionTracker;
import com.springclaw.service.context.ledger.LedgerToolRecorder;
import com.springclaw.service.proposal.ApprovedProposalContext;

/**
 * 工具上下文线程存储。
 *
 * 现在同时支持 ToolExecutionContext（基础上下文）和 AutonomousExecutionTracker（执行追踪器）。
 * Tracker 在自主循环模式下使用，记录真实的工具调用和副作用证据，
 * 解决"模型纯文本声称完成但没有实际操作"的假完成问题。
 *
 * <p>LEDGER_HOLDER 在原生消息账本路径(spec 2026-11-09 §3.4)使用:
 * 引擎在带 .tools() 的循环前挂 recorder,ToolRuntimeAspect 每次工具执行后记录,
 * 步末由引擎取走入账。异步流式(reactor 线程)读不到时自然不记录,不报错。</p>
 *
 * <p>APPROVED_HOLDER 由 ContextInjection 干路写入（不变量 4：仅后端可信路径写入），
 * ToolRuntimeAspect 仅作只读使用。
 */
public final class ToolExecutionContextHolder {

    private static final ThreadLocal<ToolExecutionContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<AutonomousExecutionTracker> TRACKER_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<LedgerToolRecorder> LEDGER_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<ApprovedProposalContext> APPROVED_HOLDER = new ThreadLocal<>();

    private ToolExecutionContextHolder() {
    }

    public static ToolExecutionContext get() {
        return HOLDER.get();
    }

    public static AutonomousExecutionTracker getTracker() {
        return TRACKER_HOLDER.get();
    }

    public static Scope open(ToolExecutionContext context) {
        ToolExecutionContext previous = HOLDER.get();
        HOLDER.set(context);
        return () -> {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        };
    }

    /**
     * 设置自主循环执行追踪器（在自主循环步骤开始前调用）。
     */
    public static void setTracker(AutonomousExecutionTracker tracker) {
        TRACKER_HOLDER.set(tracker);
    }

    /**
     * 清除自主循环执行追踪器（在自主循环结束后调用）。
     */
    public static void clearTracker() {
        TRACKER_HOLDER.remove();
    }

    /** 读取原生账本工具记录器(无则 null,工具执行侧自然不记录)。 */
    public static LedgerToolRecorder getLedgerRecorder() {
        return LEDGER_HOLDER.get();
    }

    /** 挂载原生账本工具记录器(引擎在带 .tools() 的循环前调用)。 */
    public static void setLedgerRecorder(LedgerToolRecorder recorder) {
        if (recorder == null) {
            LEDGER_HOLDER.remove();
        } else {
            LEDGER_HOLDER.set(recorder);
        }
    }

    /** 清除原生账本工具记录器(循环结束后必须调用,防线程复用泄漏)。 */
    public static void clearLedgerRecorder() {
        LEDGER_HOLDER.remove();
    }

    /**
     * 读取已批准 proposal 上下文。仅 ToolRuntimeAspect 二次校验路径调用。
     */
    public static ApprovedProposalContext getApprovedProposal() {
        return APPROVED_HOLDER.get();
    }

    /**
     * 注入已批准 proposal 上下文。
     * 不变量 4：只允许后端可信路径（ContextInjection / ProposalExecutionService）调用，
     * Aspect 不会写。
     */
    public static void setApprovedProposal(ApprovedProposalContext ctx) {
        APPROVED_HOLDER.set(ctx);
    }

    /**
     * 清除已批准 proposal 上下文（resume 结束后必须调用）。
     */
    public static void clearApprovedProposal() {
        APPROVED_HOLDER.remove();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}