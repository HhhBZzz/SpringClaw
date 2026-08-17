package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RunLifecycleObserver {

    private final RunLifecycleBridge bridge;
    private final RollbackRunContextAdapter contextAdapter;
    private final RunExecutionDecisionProjector decisionAdapter;
    private final RunResultProjector resultAdapter;
    private final List<CompletionVerifier> verifiers;
    private final boolean contextSnapshotFactoryEnabled;
    private final Map<String, Instant> turnStartedAt = new ConcurrentHashMap<>();

    @Autowired
    public RunLifecycleObserver(
            RunLifecycleBridge bridge,
            RollbackRunContextAdapter contextAdapter,
            RunExecutionDecisionProjector decisionAdapter,
            RunResultProjector resultAdapter,
            List<CompletionVerifier> verifiers,
            @Value("${springclaw.context.snapshot.factory-enabled:true}")
            boolean contextSnapshotFactoryEnabled
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.contextAdapter = Objects.requireNonNull(contextAdapter, "contextAdapter");
        this.decisionAdapter = Objects.requireNonNull(decisionAdapter, "decisionAdapter");
        this.resultAdapter = Objects.requireNonNull(resultAdapter, "resultAdapter");
        this.verifiers = List.copyOf(Objects.requireNonNull(verifiers, "verifiers"));
        this.contextSnapshotFactoryEnabled = contextSnapshotFactoryEnabled;
    }

    public RunLifecycleObserver(
            RunLifecycleBridge bridge,
            RollbackRunContextAdapter contextAdapter,
            RunExecutionDecisionProjector decisionAdapter,
            RunResultProjector resultAdapter
    ) {
        this(bridge, contextAdapter, decisionAdapter, resultAdapter,
                List.of(new TrustedModelVerifier()), true);
    }

    /** 历史签名(boolean 开关在最后)——既有测试兼容入口,verifier 用默认链。 */
    public RunLifecycleObserver(
            RunLifecycleBridge bridge,
            RollbackRunContextAdapter contextAdapter,
            RunExecutionDecisionProjector decisionAdapter,
            RunResultProjector resultAdapter,
            boolean contextSnapshotFactoryEnabled
    ) {
        this(bridge, contextAdapter, decisionAdapter, resultAdapter,
                List.of(new TrustedModelVerifier()), contextSnapshotFactoryEnabled);
    }

    public void contextAndDecisionObserved(ChatContext context, Instant at) {
        if (!contextSnapshotFactoryEnabled) {
            bridge.contextObserved(
                    context.requestId(),
                    contextAdapter.adapt(context, at),
                    at
            );
        }
        bridge.decisionObserved(
                context.requestId(),
                decisionAdapter.adapt(context, at),
                at
        );
    }

    public void executionStarted(
            ChatContext context,
            String strategyId,
            Instant at
    ) {
        bridge.executionStarted(context.requestId(), strategyId, at);
    }

    public void turnStarted(String runId, String responseMode, Instant at) {
        // 有界保护:泄漏兜底(正常路径 turnCompleted 会 remove)
        if (turnStartedAt.size() > 8192) {
            log.warn("turnStartedAt 超过 8192 条,清空重置(疑似泄漏)");
            turnStartedAt.clear();
        }
        turnStartedAt.put(runId, at);
        try {
            bridge.turnStarted(runId, responseMode, at);
        } catch (RuntimeException ex) {
            log.debug("turn.started emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    /** 对话语义(spec 2026-08-17-canonical-conversation-history §3.1):用户问题原文进事件日志。 */
    public void userMessage(String runId, String question, String responseMode, Instant at) {
        try {
            bridge.userMessage(runId, question, responseMode, at);
        } catch (RuntimeException ex) {
            log.debug("user.message emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    /** 对话语义:最终答案进事件日志(FINAL/DEGRADED 对齐 RunResult.answerKind)。 */
    public void assistantAnswer(String runId, String answer, String answerKind, Instant at) {
        try {
            bridge.assistantAnswer(runId, answer, answerKind, at);
        } catch (RuntimeException ex) {
            log.debug("assistant.answer emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    private void turnCompleted(String runId, String outcome, Instant at) {
        Instant startedAt = turnStartedAt.remove(runId);
        long durationMs = startedAt == null ? 0L
                : Math.max(0L, at.toEpochMilli() - startedAt.toEpochMilli());
        try {
            bridge.turnCompleted(runId, outcome, durationMs, at);
        } catch (RuntimeException ex) {
            log.debug("turn.completed emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    /**
     * step 边界作用域：try-with-resources 的 close() 发射 step.completed。
     * outcome 默认 "ok"；正常迭代结束、break/continue 提前退出均按 "ok" 记——
     * 失败事实由 run.failed/tool.failed 等事件承载，step 事件只标边界。
     * emit 失败时返回 NOOP 作用域，不影响引擎主路径。
     */
    public StepScope beginStep(String runId, int stepIndex, String stepKind) {
        try {
            bridge.stepStarted(runId, stepIndex, stepKind, Instant.now());
            return new StepScope(runId, stepIndex, stepKind, System.nanoTime());
        } catch (RuntimeException ex) {
            log.debug("step.started emit 失败, runId={}, reason={}", runId, ex.getMessage());
            return new StepScope(null, 0, "", 0L);
        }
    }

    public final class StepScope implements AutoCloseable {

        private final String runId;
        private final int stepIndex;
        private final String stepKind;
        private final long startedNano;
        private String outcome = "ok";

        private StepScope(String runId, int stepIndex, String stepKind, long startedNano) {
            this.runId = runId;
            this.stepIndex = stepIndex;
            this.stepKind = stepKind;
            this.startedNano = startedNano;
        }

        /** 步边界事实标注：terminal/max_steps_reached/failed 等。 */
        public void outcome(String value) {
            if (value != null) {
                this.outcome = value;
            }
        }

        @Override
        public void close() {
            if (runId == null) {
                return;
            }
            long durationMs = Math.max(0L, (System.nanoTime() - startedNano) / 1_000_000L);
            try {
                bridge.stepCompleted(runId, stepIndex, stepKind, outcome, durationMs, Instant.now());
            } catch (RuntimeException ex) {
                log.debug("step.completed emit 失败, runId={}, reason={}", runId, ex.getMessage());
            }
        }
    }

    public void confirmationRequired(
            String runId,
            String proposalId,
            Instant at
    ) {
        bridge.confirmationRequired(runId, proposalId, at);
    }

    public void confirmationApproved(String runId, Instant at) {
        bridge.confirmationApproved(runId, at);
    }

    public void confirmationRejected(
            String runId,
            String reason,
            Instant at
    ) {
        bridge.confirmationRejected(
                runId,
                new RunState.Failure(
                        "CONFIRMATION_REJECTED",
                        reason == null ? "" : reason,
                        false
                ),
                at
        );
    }

    public void toolStarted(String runId, Instant at) {
        try {
            bridge.toolStarted(runId, at);
        } catch (RuntimeException ex) {
            log.debug("tool.started emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    public void toolSucceeded(String runId, Instant at) {
        try {
            bridge.toolSucceeded(runId, at);
        } catch (RuntimeException ex) {
            log.debug("tool.succeeded emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    public void toolFailed(String runId, Instant at) {
        try {
            bridge.toolFailed(runId, at);
        } catch (RuntimeException ex) {
            log.debug("tool.failed emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    public void toolStarted(String runId, String toolName, Instant at) {
        try {
            bridge.toolStarted(runId, toolName, at);
        } catch (RuntimeException ex) {
            log.debug("tool.started emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    public void toolSucceeded(String runId, String toolName, long durationMs, Instant at) {
        try {
            bridge.toolSucceeded(runId, toolName, durationMs, at);
        } catch (RuntimeException ex) {
            log.debug("tool.succeeded emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    public void toolFailed(String runId, String toolName, String errorCode, Instant at) {
        try {
            bridge.toolFailed(runId, toolName, errorCode, at);
        } catch (RuntimeException ex) {
            log.debug("tool.failed emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    /**
     * 终态入口：CompletionVerifier 链判定后分流 completed/degraded/failed。
     * turn.completed 在判定后发射（终态后 observation 追加合法）。
     */
    public void resultReturned(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer,
            Instant at
    ) {
        CompletionVerdict verdict = resolveVerdict(context, executionResult, answer);
        turnCompleted(context.requestId(), verdict.outcome().name(), at);
        bridge.verificationStarted(context.requestId(), at);
        if (verdict.outcome() == CompletionDecision.Outcome.FAIL) {
            bridge.failed(
                    context.requestId(),
                    new RunState.Failure(verdict.reasonCode(), verdict.summary(), false),
                    at
            );
            return;
        }
        RunResultProjector.TerminalObservation observation =
                resultAdapter.adaptTerminal(context, executionResult, answer, verdict, at);
        if (verdict.outcome() == CompletionDecision.Outcome.COMPLETE) {
            bridge.completed(
                    context.requestId(),
                    observation.decision(),
                    observation.result(),
                    at
            );
            return;
        }
        bridge.degraded(
                context.requestId(),
                observation.decision(),
                observation.result(),
                at
        );
    }

    private CompletionVerdict resolveVerdict(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer
    ) {
        for (CompletionVerifier verifier : verifiers) {
            if (verifier.supports(context, executionResult, answer)) {
                return verifier.verify(context, executionResult, answer);
            }
        }
        return new CompletionVerdict(
                CompletionDecision.Outcome.DEGRADE,
                "NO_COMPLETION_VERIFIER",
                "无可用完成判定器，按降级处理。"
        );
    }

    public void failed(
            String runId,
            String failureCode,
            Throwable error,
            Instant at
    ) {
        turnCompleted(runId, "FAILED", at);
        String message = error == null || error.getMessage() == null
                ? ""
                : error.getMessage();
        bridge.failed(
                runId,
                new RunState.Failure(failureCode, message, false),
                at
        );
    }
}
