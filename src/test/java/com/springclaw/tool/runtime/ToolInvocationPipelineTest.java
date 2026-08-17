package com.springclaw.tool.runtime;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolInvocationPipeline 契约测试(dsh 工具管线参照,Aspect 管线化)。
 * 管线职责:按注册顺序过 guard 段(可否决)、单点 execute、收尾观察段(成功/失败/否决均走)。
 */
class ToolInvocationPipelineTest {

    private record ToolCall(String name, Object[] args) implements ToolInvocation {
        @Override
        public String toolName() {
            return name;
        }

        @Override
        public Object[] args() {
            return args;
        }
    }

    @Test
    void guardsRunInRegistrationOrderAndCanVeto() {
        StringBuilder trace = new StringBuilder();
        ToolInvocationPipeline pipeline = ToolInvocationPipeline.builder()
                .addGuard(inv -> {
                    trace.append("g1;");
                    return ToolInvocationPipeline.GuardDecision.proceed();
                })
                .addGuard(inv -> {
                    trace.append("g2;");
                    return ToolInvocationPipeline.GuardDecision.veto(40301, "denied by g2");
                })
                .addGuard(inv -> {
                    trace.append("g3;"); // 不应执行(g2 否决)
                    return ToolInvocationPipeline.GuardDecision.proceed();
                })
                .onExecute(inv -> {
                    trace.append("exec;");
                    return "ok";
                })
                .onComplete((inv, outcome) -> trace.append("done:").append(outcome.status()))
                .build();

        assertThatThrownBy(() -> pipeline.invoke(new ToolCall("t", new Object[0])))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("denied by g2");
        assertThat(trace.toString()).isEqualTo("g1;g2;done:denied");
    }

    @Test
    void allGuardsPassThenExecuteAndCompleteSuccess() throws Throwable {
        AtomicInteger executions = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        ToolInvocationPipeline pipeline = ToolInvocationPipeline.builder()
                .addGuard(inv -> ToolInvocationPipeline.GuardDecision.proceed())
                .onExecute(inv -> {
                    executions.incrementAndGet();
                    return inv.args()[0];
                })
                .onComplete((inv, outcome) -> seen.add(outcome.status()))
                .build();

        Object result = pipeline.invoke(new ToolCall("t", new Object[]{"payload"}));

        assertThat(result).isEqualTo("payload");
        assertThat(executions.get()).isEqualTo(1);
        assertThat(seen).containsExactly("success");
    }

    @Test
    void executeThrowingStillReachesCompleteWithFailedStatus() {
        List<String> statuses = new ArrayList<>();
        ToolInvocationPipeline pipeline = ToolInvocationPipeline.builder()
                .onExecute(inv -> {
                    throw new IllegalStateException("boom");
                })
                .onComplete((inv, outcome) -> statuses.add(outcome.status()))
                .build();

        assertThatThrownBy(() -> pipeline.invoke(new ToolCall("t", new Object[0])))
                .isInstanceOf(IllegalStateException.class);
        assertThat(statuses).containsExactly("failed");
    }

    @Test
    void guardVetoCarriesAuditDetailThroughOutcome() {
        List<ToolInvocationPipeline.Outcome> outcomes = new ArrayList<>();
        ToolInvocationPipeline pipeline = ToolInvocationPipeline.builder()
                .addGuard(inv -> ToolInvocationPipeline.GuardDecision.veto(40301, "rate limited"))
                .onExecute(inv -> "unreachable")
                .onComplete((inv, outcome) -> outcomes.add(outcome))
                .build();

        try {
            pipeline.invoke(new ToolCall("t", new Object[0]));
        } catch (Throwable ignored) {
        }

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).status()).isEqualTo("denied");
        assertThat(outcomes.get(0).detail()).isEqualTo("rate limited");
        assertThat(outcomes.get(0).result()).isNull();
    }

    @Test
    void vetoedGuardBlocksExecutionEntirely() throws Throwable {
        AtomicInteger executions = new AtomicInteger();
        ToolInvocationPipeline pipeline = ToolInvocationPipeline.builder()
                .addGuard(inv -> ToolInvocationPipeline.GuardDecision.veto(40062, "bad command"))
                .onExecute(inv -> {
                    executions.incrementAndGet();
                    return "should not run";
                })
                .build();

        try {
            pipeline.invoke(new ToolCall("t", new Object[0]));
        } catch (Exception ignored) {
        }
        assertThat(executions.get()).isZero();
    }
}
