package com.springclaw.tool.runtime;

import com.springclaw.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 工具调用管线(dsh 工具瀑布参照):guard 段按注册顺序执行(可否决),
 * 全过后单点 execute,收尾观察段无论成功/否决/异常都走一次。
 *
 * <p>与 ToolRuntimeAspect 的关系:切面负责 AOP 捕获(签名/参数/上下文),
 * 把执行策略交给本管线——权限/限流/命令白名单是 guard 段,proceed 是
 * execute 段,审计/事件收尾是 complete 段。新增策略=加 guard/observer,
 * 不再在 350 行切面方法里插代码。</p>
 */
public class ToolInvocationPipeline {

    /** guard 段裁决:proceed 放行 / veto 以 BusinessException 否决(code+detail)。 */
    public record GuardDecision(boolean allowed, int vetoCode, String vetoDetail) {

        public static GuardDecision proceed() {
            return new GuardDecision(true, 0, "");
        }

        public static GuardDecision veto(int code, String detail) {
            return new GuardDecision(false, code, detail);
        }
    }

    /** 管线终局:status ∈ success/failed/denied;denied 时 result 为 null。 */
    public record Outcome(String status, Object result, Throwable error, String detail) {
    }

    @FunctionalInterface
    public interface Guard {
        GuardDecision check(ToolInvocation invocation);
    }

    @FunctionalInterface
    public interface Executor {
        Object execute(ToolInvocation invocation) throws Throwable;
    }

    @FunctionalInterface
    public interface CompletionObserver {
        void onComplete(ToolInvocation invocation, Outcome outcome) throws Throwable;
    }

    private final List<Guard> guards;
    private final Executor executor;
    private final List<CompletionObserver> observers;

    private ToolInvocationPipeline(List<Guard> guards, Executor executor, List<CompletionObserver> observers) {
        this.guards = guards;
        this.executor = executor;
        this.observers = observers;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 跑一次完整管线。guard 否决 → BusinessException(detail 供审计);
     * execute 异常原样上抛;两类路径都先过 observers 再抛/返。
     */
    public Object invoke(ToolInvocation invocation) throws Throwable {
        for (Guard guard : guards) {
            GuardDecision decision = guard.check(invocation);
            if (!decision.allowed()) {
                complete(invocation, new Outcome("denied", null, null, decision.vetoDetail()));
                throw new BusinessException(decision.vetoCode(), decision.vetoDetail());
            }
        }
        Object result;
        try {
            result = executor.execute(invocation);
        } catch (Throwable ex) {
            complete(invocation, new Outcome("failed", null, ex, ex.getMessage()));
            throw ex;
        }
        complete(invocation, new Outcome("success", result, null, ""));
        return result;
    }

    private void complete(ToolInvocation invocation, Outcome outcome) throws Throwable {
        for (CompletionObserver observer : observers) {
            observer.onComplete(invocation, outcome);
        }
    }

    public static final class Builder {

        private final List<Guard> guards = new ArrayList<>();
        private final List<CompletionObserver> observers = new ArrayList<>();
        private Executor executor = invocation -> null;

        public Builder addGuard(Guard guard) {
            guards.add(guard);
            return this;
        }

        public Builder onExecute(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder onComplete(CompletionObserver observer) {
            observers.add(observer);
            return this;
        }

        public ToolInvocationPipeline build() {
            return new ToolInvocationPipeline(List.copyOf(guards), executor, List.copyOf(observers));
        }
    }
}
