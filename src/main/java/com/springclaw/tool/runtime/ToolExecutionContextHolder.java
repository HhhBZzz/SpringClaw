package com.springclaw.tool.runtime;

import com.springclaw.service.chat.impl.AutonomousExecutionTracker;

/**
 * 工具上下文线程存储。
 *
 * 现在同时支持 ToolExecutionContext（基础上下文）和 AutonomousExecutionTracker（执行追踪器）。
 * Tracker 在自主循环模式下使用，记录真实的工具调用和副作用证据，
 * 解决"模型纯文本声称完成但没有实际操作"的假完成问题。
 */
public final class ToolExecutionContextHolder {

    private static final ThreadLocal<ToolExecutionContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<AutonomousExecutionTracker> TRACKER_HOLDER = new ThreadLocal<>();

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

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}