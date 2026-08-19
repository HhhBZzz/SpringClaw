package com.springclaw.runtime.history;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** ConversationHistoryDeriver 测试工厂:构造完整状态机链所需的最小 acceptance/snapshot/decision/result。 */
final class TestRunSupport {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestRunSupport() {
    }

    static int sequence() {
        return (int) SEQ.incrementAndGet();
    }

    static String newRunId() {
        return String.format("%032x", 0x1000000000000000L + COUNTER.incrementAndGet());
    }

    static RunAcceptance acceptance(String runId, String sessionKey) {
        return acceptanceAt(runId, sessionKey, Instant.parse("2026-08-17T00:00:00Z"));
    }

    static RunAcceptance acceptanceAt(String runId, String sessionKey, Instant at) {
        return new RunAcceptance(
                runId, sessionKey, "api", "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api", sessionKey, "user-1"
                ),
                "USER", "hello",
                "agent", at, at.plusSeconds(300),
                null
        );
    }

    static ContextSnapshot snapshot(String runId) {
        return new ContextSnapshot(
                runId, "session-1", "user-1", "api", "user-1", "USER",
                "hello", "hello", "system", "", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), memoryFrame(runId),
                Instant.parse("2026-08-17T00:00:01Z"), "snapshot-hash"
        );
    }

    private static MemoryFrame memoryFrame(String runId) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api", "session-1", "user-1"
                )),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("source", "test"), List.of(),
                Instant.parse("2026-08-17T00:00:00Z"), "frame-hash-" + runId
        );
    }

    static ExecutionDecision decision(String runId) {
        return new ExecutionDecision(
                runId, "general", "answer", "agent", "read", List.of(),
                List.of(), Map.of(), List.of(), 1.0, "legacy", "legacy",
                Instant.parse("2026-08-17T00:00:02Z")
        );
    }

    static CompletionDecision completionAt(String runId, Instant at) {
        return new CompletionDecision(
                runId, CompletionDecision.Outcome.COMPLETE, "TEST_COMPLETE", "test",
                List.of("test"), List.of(), false, 0, 0.8, at
        );
    }

    static RunResult resultAt(String runId, Instant at) {
        return new RunResult(
                runId, RunStatus.COMPLETED, "answer", RunResult.AnswerKind.FINAL,
                "legacy", "legacy", List.of("legacy"), List.of(), 0.8,
                Map.of(), "", "", at
        );
    }
}
