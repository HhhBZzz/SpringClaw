package com.springclaw.tool.runtime;

/**
 * 工具上下文线程存储。
 */
public final class ToolExecutionContextHolder {

    private static final ThreadLocal<ToolExecutionContext> HOLDER = new ThreadLocal<>();

    private ToolExecutionContextHolder() {
    }

    public static ToolExecutionContext get() {
        return HOLDER.get();
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

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
