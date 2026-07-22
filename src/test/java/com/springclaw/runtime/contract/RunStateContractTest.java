package com.springclaw.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateContractTest {

    private static final Instant T0 = Instant.parse("2026-06-19T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-19T00:00:01Z");
    private static final Instant T2 = Instant.parse("2026-06-19T00:00:02Z");
    private static final Instant T3 = Instant.parse("2026-06-19T00:00:03Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void runStateExposesNoBuilderApi() {
        assertThat(Arrays.stream(RunState.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("builder", "toBuilder");
        assertThat(Arrays.stream(RunState.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .doesNotContain("Builder");
    }

    @Test
    void runIdMustEqualRequestId() {
        assertThatThrownBy(() -> state(
                "run-1", "request-2", 0, RunStatus.CREATED, T0, null,
                null, null, "", 1, "", List.of(), null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equal");
    }

    @Test
    void nestedContractRunIdentifiersMustMatchState() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-2"), null, "", 1, "", List.of(), null, null, null
        )).hasMessageContaining("ContextSnapshot");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), decision("run-2"), "", 1, "", List.of(), null, null, null
        )).hasMessageContaining("ExecutionDecision");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(invocation("run-2")), null, null, null
        )).hasMessageContaining("ToolInvocation");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completion("run-2", CompletionDecision.Outcome.RETRY, 2), null, null
        )).hasMessageContaining("CompletionDecision");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 5, RunStatus.COMPLETED, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-2", RunStatus.COMPLETED), null
        )).hasMessageContaining("RunResult");
    }

    @Test
    void toolInvocationListRejectsNullEntriesAsContractViolations() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                Arrays.asList((ToolInvocation) null), null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolInvocations");
    }

    @Test
    void toolInvocationListRejectsDuplicateInvocationIds() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(
                        invocationWithIdempotencyKey("inv-1", "key-1", 1),
                        invocationWithIdempotencyKey("inv-1", "key-2", 1)
                ),
                null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invocationId");
    }

    @Test
    void toolInvocationListRejectsDuplicateIdempotencyKeys() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(
                        invocationWithIdempotencyKey("inv-1", "shared-key", 1),
                        invocationWithIdempotencyKey("inv-2", "shared-key", 1)
                ),
                null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void terminalStatesRequireMatchingResultStatusAndCompletionOutcome() {
        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.DEGRADE, 0),
                result("run-1", RunStatus.COMPLETED),
                null
        )).hasMessageContaining("COMPLETE");

        assertThatThrownBy(() -> terminalState(
                RunStatus.DEGRADED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.DEGRADED),
                null
        )).hasMessageContaining("DEGRADE");

        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.DEGRADED),
                null
        )).hasMessageContaining("status");

        assertThatThrownBy(() -> terminalState(
                RunStatus.DEGRADED,
                null,
                result("run-1", RunStatus.DEGRADED),
                null
        )).hasMessageContaining("CompletionDecision");

        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                null,
                null
        )).hasMessageContaining("RunResult");
    }

    @Test
    void nonterminalStateRejectsRunResult() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, result("run-1", RunStatus.COMPLETED), null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonterminal");
    }

    @Test
    void failedRequiresFailureAndFinishedAtButAllowsEarlyFailureWithoutResultOrDecision() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, T1,
                null, null, "", 1, "", List.of(), null, null, null
        )).hasMessageContaining("failure");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, null,
                null, null, "", 1, "", List.of(), null, null, failure()
        )).hasMessageContaining("finishedAt");

        RunState earlyFailure = state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, T1,
                null, null, "", 1, "", List.of(), null, null, failure()
        );
        assertThat(earlyFailure.result()).isNull();
        assertThat(earlyFailure.completionDecision()).isNull();
    }

    @Test
    void failedOptionalResultAndDecisionMustExpressFailure() {
        assertThatThrownBy(() -> terminalState(
                RunStatus.FAILED,
                completion("run-1", CompletionDecision.Outcome.FAIL, 0),
                result("run-1", RunStatus.COMPLETED),
                failure()
        )).hasMessageContaining("status");

        assertThatThrownBy(() -> terminalState(
                RunStatus.FAILED,
                completion("run-1", CompletionDecision.Outcome.DEGRADE, 0),
                result("run-1", RunStatus.FAILED),
                failure()
        )).hasMessageContaining("FAIL");
    }

    @Test
    void runStateRejectsBackwardChronology() {
        assertThatThrownBy(() -> runningAt(
                T1, T1, T0, null, T3.plusSeconds(30), null
        )).hasMessageContaining("acceptedAt");

        assertThatThrownBy(() -> runningAt(
                T1, T1, T2, null, T0, null
        )).hasMessageContaining("deadlineAt");

        assertThatThrownBy(() -> runningAt(
                T1, T0, T2, null, T3.plusSeconds(30), null
        )).hasMessageContaining("startedAt");

        assertThatThrownBy(() -> runningAt(
                T0, T3, T2, null, T3.plusSeconds(30), null
        )).hasMessageContaining("startedAt");

        assertThatThrownBy(() -> runningAt(
                T1, null, T2, T0, T3.plusSeconds(30), null
        )).hasMessageContaining("finishedAt");

        assertThatThrownBy(() -> runningAt(
                T0, T2, T3, T1, T3.plusSeconds(30), null
        )).hasMessageContaining("finishedAt");

        assertThatThrownBy(() -> runningAt(
                T0, T1, T2, T3, T3.plusSeconds(30), null
        )).hasMessageContaining("finishedAt");
    }

    @Test
    void deadlineMayBeExceededWhenRecordingTimeoutOrRecovery() {
        Instant deadline = T1;
        Instant recoveredAt = T3;
        assertThatCode(() -> new RunState(
                "run-1",
                "run-1",
                4,
                RunStatus.FAILED,
                "session-1",
                "web",
                "user-1",
                personalClaim("web", "session-1", "user-1"),
                "USER",
                "hello",
                "agent",
                T0,
                T1,
                recoveredAt,
                recoveredAt,
                deadline,
                snapshot("run-1"),
                decision("run-1"),
                "strategy-1",
                1,
                "",
                List.of(),
                completion("run-1", CompletionDecision.Outcome.FAIL, 0),
                result("run-1", RunStatus.FAILED, recoveredAt, "failed"),
                Map.of(),
                failure(),
                null
        )).doesNotThrowAnyException();
    }

    @Test
    void nonterminalStatesRejectFinishedAtAndFailure() {
        assertThatThrownBy(() -> runningAt(
                T0, T1, T2, T2, T3.plusSeconds(30), null
        )).hasMessageContaining("finishedAt");

        assertThatThrownBy(() -> runningAt(
                T0, T1, T2, null, T3.plusSeconds(30), failure()
        )).hasMessageContaining("failure");
    }

    @Test
    void successfulTerminalStatesRejectFailure() {
        assertThatThrownBy(() -> terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.COMPLETED),
                failure()
        )).hasMessageContaining("failure");

        assertThatThrownBy(() -> terminalState(
                RunStatus.DEGRADED,
                completion("run-1", CompletionDecision.Outcome.DEGRADE, 0),
                result("run-1", RunStatus.DEGRADED),
                failure()
        )).hasMessageContaining("failure");
    }

    @Test
    void stateRejectsInvocationAttemptBeyondRunAttempt() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(invocation("inv-2", "run-1", 2)), null, null, null
        )).hasMessageContaining("attempt");
    }

    @Test
    void terminalResultMustShareCanonicalFinishedInstantAndFailureCode() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 5, RunStatus.COMPLETED, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.COMPLETED, T2, ""), null
        )).hasMessageContaining("completedAt");

        assertThatThrownBy(() -> state(
                "run-1", "run-1", 5, RunStatus.FAILED, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.FAIL, 0),
                result("run-1", RunStatus.FAILED, T3, "different-code"), failure()
        )).hasMessageContaining("failureCode");
    }

    @Test
    void waitingConfirmationRequiresPendingProposalId() {
        assertThatThrownBy(() -> state(
                "run-1", "run-1", 1, RunStatus.WAITING_CONFIRMATION, T1, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        )).hasMessageContaining("pendingProposalId");
    }

    @Test
    void runStateRequiresClaimToMatchAcceptedSessionChannelAndUser() {
        assertThatThrownBy(() -> claimVariant(
                createdState(),
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                )
        )).hasMessageContaining("channel");

        assertThatThrownBy(() -> claimVariant(
                createdState(),
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "web",
                        "session-2",
                        "user-1"
                )
        )).hasMessageContaining("sessionKey");

        assertThatThrownBy(() -> claimVariant(
                createdState(),
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "web",
                        "session-1",
                        "user-2"
                )
        )).hasMessageContaining("acceptedUserId");
    }

    @Test
    void runStateNormalizesIdentityBeforeComparingClaim() {
        RunState normalized = identityVariant(
                createdState(),
                " session-1 ",
                " web ",
                " user-1 "
        );

        assertThat(normalized.sessionKey()).isEqualTo("session-1");
        assertThat(normalized.channel()).isEqualTo("web");
        assertThat(normalized.userId()).isEqualTo("user-1");
    }

    @Test
    void transitionRetainsIdentityIncrementsRevisionUsesAllowedEdgeAndMonotonicTime() {
        RunState created = createdState();

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-2", "run-2", 1, RunStatus.CONTEXT_READY, T1, null,
                        snapshot("run-2"), null, "", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("identity");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-1", "run-1", 2, RunStatus.CONTEXT_READY, T1, null,
                        snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("revision");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-1", "run-1", 1, RunStatus.RUNNING, T1, null,
                        null, null, "strategy-1", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("CREATED -> RUNNING");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                state(
                        "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T0.minusSeconds(1), null,
                        snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
                )
        )).hasMessageContaining("updatedAt");
    }

    @Test
    void transitionRequiresEvidenceForContextDecisionAndRunningStages() {
        RunState created = createdState();
        RunState contextReadyWithoutSnapshot = state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                null, null, "", 1, "", List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(created, contextReadyWithoutSnapshot))
                .hasMessageContaining("contextSnapshot");

        RunState contextReady = contextReadyState();
        RunState decidedWithoutDecision = state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(contextReady, decidedWithoutDecision))
                .hasMessageContaining("executionDecision");

        RunState decided = decidedState();
        RunState runningWithoutStrategy = state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "", 1, "",
                List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(decided, runningWithoutStrategy))
                .hasMessageContaining("strategyId");
    }

    @Test
    void transitionsPreserveImmutableAcceptanceHistory() {
        RunState valid = nextState(
                createdState(), 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null,
                Map.of(), null
        );

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, "session-2", valid.channel(), valid.userId(),
                        valid.roleCodeAtAcceptance(), valid.originalMessage(), valid.responseMode(),
                        valid.acceptedAt(), valid.deadlineAt())
        )).hasMessageContaining("sessionKey");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), "api", valid.userId(),
                        valid.roleCodeAtAcceptance(), valid.originalMessage(), valid.responseMode(),
                        valid.acceptedAt(), valid.deadlineAt())
        )).hasMessageContaining("channel");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), valid.channel(), "user-2",
                        valid.roleCodeAtAcceptance(), valid.originalMessage(), valid.responseMode(),
                        valid.acceptedAt(), valid.deadlineAt())
        )).hasMessageContaining("userId");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), valid.channel(), valid.userId(),
                        "ADMIN", valid.originalMessage(), valid.responseMode(),
                        valid.acceptedAt(), valid.deadlineAt())
        )).hasMessageContaining("roleCodeAtAcceptance");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), valid.channel(), valid.userId(),
                        valid.roleCodeAtAcceptance(), "changed", valid.responseMode(),
                        valid.acceptedAt(), valid.deadlineAt())
        )).hasMessageContaining("originalMessage");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), valid.channel(), valid.userId(),
                        valid.roleCodeAtAcceptance(), valid.originalMessage(), "basic",
                        valid.acceptedAt(), valid.deadlineAt())
        )).hasMessageContaining("responseMode");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), valid.channel(), valid.userId(),
                        valid.roleCodeAtAcceptance(), valid.originalMessage(), valid.responseMode(),
                        T0.minusSeconds(1), valid.deadlineAt())
        )).hasMessageContaining("acceptedAt");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                acceptanceVariant(valid, valid.sessionKey(), valid.channel(), valid.userId(),
                        valid.roleCodeAtAcceptance(), valid.originalMessage(), valid.responseMode(),
                        valid.acceptedAt(), valid.deadlineAt().plusSeconds(1))
        )).hasMessageContaining("deadlineAt");
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                claimVariant(
                        valid,
                        SessionAccessClaim.personal(
                                SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK,
                                valid.channel(),
                                valid.sessionKey(),
                                valid.userId()
                        )
                )
        )).hasMessageContaining("sessionAccessClaim");
    }

    @Test
    void transitionsPreserveContextSnapshotHistory() {
        RunState created = createdState();
        RunState failedWithPrematureSnapshot = nextState(
                created, 1, RunStatus.FAILED, T1, T1,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null,
                Map.of(), failure()
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                failedWithPrematureSnapshot
        )).hasMessageContaining("contextSnapshot");

        RunState contextReady = contextReadyState();
        RunState disappeared = nextState(
                contextReady, 2, RunStatus.DECIDED, T2, null,
                null, decision("run-1"), "", 1, "", List.of(), null, null,
                Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                contextReady,
                disappeared
        )).hasMessageContaining("contextSnapshot");

        RunState replaced = nextState(
                contextReady, 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1", "hash-2"), decision("run-1"), "", 1, "",
                List.of(), null, null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                contextReady,
                replaced
        )).hasMessageContaining("contextSnapshot");
    }

    @Test
    void transitionsPreserveDecisionExceptDuringVerificationRetry() {
        RunState decided = decidedState();
        RunState disappeared = nextState(
                decided, 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), null, "strategy-1", 1, "", List.of(), null, null,
                Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                decided,
                disappeared
        )).hasMessageContaining("executionDecision");

        RunState running = runningState();
        RunState replaced = nextState(
                running, 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1", "changed-goal"), "strategy-1", 1, "",
                List.of(), null, null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                replaced
        )).hasMessageContaining("executionDecision");
    }

    @Test
    void executionDecisionMayFirstAppearOnlyOnContextReadyToDecided() {
        RunState created = createdState();
        RunState createdFailureWithDecision = nextState(
                created, 1, RunStatus.FAILED, T1, T1,
                null, decision("run-1"), "", 1, "", List.of(), null, null,
                Map.of(), failure()
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                createdFailureWithDecision
        )).hasMessageContaining("executionDecision");

        RunState contextReadyWithDecision = nextState(
                created, 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-1"), decision("run-1"), "", 1, "", List.of(), null, null,
                Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                created,
                contextReadyWithDecision
        )).hasMessageContaining("executionDecision");

        RunState decisionlessDecided = state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
        );
        RunState decidedFailureWithDecision = nextState(
                decisionlessDecided, 3, RunStatus.FAILED, T3, T3,
                snapshot("run-1"), decision("run-1"), "", 1, "", List.of(), null, null,
                Map.of(), failure()
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                decisionlessDecided,
                decidedFailureWithDecision
        )).hasMessageContaining("executionDecision");

        RunState contextReady = contextReadyState();
        RunState decided = nextState(
                contextReady, 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), decision("run-1"), "", 1, "", List.of(), null, null,
                Map.of(), null
        );
        RunTransitionPolicy.validate(contextReady, decided);
    }

    @Test
    void transitionsPreserveStrategyUntilRetryClearsItForReselection() {
        RunState running = runningState();
        RunState disappeared = nextState(
                running, 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "", 1, "", List.of(), null, null,
                Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                disappeared
        )).hasMessageContaining("strategyId");

        RunState changed = nextState(
                running, 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-2", 1, "",
                List.of(), null, null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                changed
        )).hasMessageContaining("strategyId");

        RunState retryWithOldStrategy = nextState(
                verifyingState(1, null), 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "strategy-1", 2, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.RETRY, 2),
                null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                verifyingState(1, null),
                retryWithOldStrategy
        )).hasMessageContaining("strategyId");
    }

    @Test
    void transitionsPreserveStartedAtOnceEstablished() {
        RunState created = createdState();
        RunState contextReady = stateWithStartedAt(
                created, 1, RunStatus.CONTEXT_READY, T1, null, T1,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null,
                Map.of(), null
        );
        RunTransitionPolicy.validate(created, contextReady);

        RunState running = runningState();
        RunState clearedStart = stateWithStartedAt(
                running, 4, RunStatus.VERIFYING, T3, null, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(running, clearedStart))
                .hasMessageContaining("startedAt");

        RunState changedStart = stateWithStartedAt(
                running, 4, RunStatus.VERIFYING, T3, null, T2,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(running, changedStart))
                .hasMessageContaining("startedAt");
    }

    @Test
    void transitionsRejectInvocationRemovalReorderAndRequestMutation() {
        ToolInvocation first = invocation("inv-1", "run-1", 1);
        ToolInvocation second = invocation("inv-2", "run-1", 1);
        RunState running = state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(first, second), null, null, Map.of("tokens", 10L), null
        );

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 4, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                        List.of(first), null, null, Map.of("tokens", 10L), null
                )
        )).hasMessageContaining("toolInvocations");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 4, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                        List.of(second, first), null, null, Map.of("tokens", 10L), null
                )
        )).hasMessageContaining("toolInvocations");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 4, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                        List.of(invocationWithCapability(
                                "inv-1", "run-1", 1, "changed-capability"
                        ), second),
                        null, null, Map.of("tokens", 10L), null
                )
        )).hasMessageContaining("toolInvocations");
    }

    @Test
    void transitionsAllowCanonicalInvocationLifecycleProgression() {
        ToolInvocation requested = lifecycleInvocation(
                ToolInvocation.Status.REQUESTED, "", null, null, null
        );
        ToolInvocation waiting = lifecycleInvocation(
                ToolInvocation.Status.WAITING_CONFIRMATION,
                "proposal-1", null, null, null
        );
        ToolInvocation approved = lifecycleInvocation(
                ToolInvocation.Status.APPROVED,
                "proposal-1", null, null, null
        );
        ToolInvocation running = lifecycleInvocation(
                ToolInvocation.Status.RUNNING,
                "proposal-1", T1, null, null
        );
        ToolInvocation succeeded = lifecycleInvocation(
                ToolInvocation.Status.SUCCEEDED,
                "proposal-1", T1, T2, successfulOutcome(T2)
        );

        assertInvocationProgressionAccepted(requested, waiting);
        assertInvocationProgressionAccepted(waiting, approved);
        assertInvocationProgressionAccepted(approved, running);
        assertInvocationProgressionAccepted(running, succeeded);
        assertInvocationProgressionAccepted(succeeded, succeeded);
    }

    @Test
    void verificationRetryPreservesInvocationListExactly() {
        ToolInvocation requested = lifecycleInvocation(
                ToolInvocation.Status.REQUESTED, "", null, null, null
        );
        RunState verifying = state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(requested), null, null, Map.of(), null
        );

        ToolInvocation progressed = lifecycleInvocation(
                ToolInvocation.Status.RUNNING, "", T1, null, null
        );
        RunState retryWithProgression = nextState(
                verifying, 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 2, "",
                List.of(progressed),
                completion("run-1", CompletionDecision.Outcome.RETRY, 2),
                null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(verifying, retryWithProgression))
                .hasMessageContaining("toolInvocations");

        ToolInvocation newAttemptInvocation = invocation("inv-2", "run-1", 2);
        RunState retryWithAppend = nextState(
                verifying, 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 2, "",
                List.of(requested, newAttemptInvocation),
                completion("run-1", CompletionDecision.Outcome.RETRY, 2),
                null, Map.of(), null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(verifying, retryWithAppend))
                .hasMessageContaining("toolInvocations");
    }

    @Test
    void transitionsRejectInvalidInvocationStatusProgression() {
        assertInvocationProgressionRejected(
                lifecycleInvocation(ToolInvocation.Status.REQUESTED, "", null, null, null),
                lifecycleInvocation(
                        ToolInvocation.Status.SUCCEEDED,
                        "", T1, T2, successfulOutcome(T2)
                )
        );
        assertInvocationProgressionRejected(
                lifecycleInvocation(
                        ToolInvocation.Status.WAITING_CONFIRMATION,
                        "proposal-1", null, null, null
                ),
                lifecycleInvocation(
                        ToolInvocation.Status.RUNNING,
                        "proposal-1", T1, null, null
                )
        );
        assertInvocationProgressionRejected(
                lifecycleInvocation(
                        ToolInvocation.Status.RUNNING,
                        "", T1, null, null
                ),
                lifecycleInvocation(
                        ToolInvocation.Status.DENIED,
                        "", T1, T2, failedOutcome(T2)
                )
        );
        assertInvocationProgressionRejected(
                lifecycleInvocation(
                        ToolInvocation.Status.SUCCEEDED,
                        "", T1, T2, successfulOutcome(T2)
                ),
                lifecycleInvocation(
                        ToolInvocation.Status.FAILED,
                        "", T1, T2, failedOutcome(T2)
                )
        );
    }

    @Test
    void transitionsPreserveInvocationLifecycleIdentityFields() {
        ToolInvocation requested = lifecycleInvocation(
                ToolInvocation.Status.REQUESTED, "", null, null, null
        );

        assertInvocationProgressionRejected(
                requested,
                lifecycleInvocation(
                        ToolInvocation.Status.APPROVED,
                        "proposal-1", null, null, null
                )
        );
        assertInvocationProgressionRejected(
                lifecycleInvocation(
                        ToolInvocation.Status.WAITING_CONFIRMATION,
                        "proposal-1", null, null, null
                ),
                lifecycleInvocation(
                        ToolInvocation.Status.APPROVED,
                        "", null, null, null
                )
        );
        assertInvocationProgressionRejected(
                lifecycleInvocation(
                        ToolInvocation.Status.WAITING_CONFIRMATION,
                        "proposal-1", null, null, null
                ),
                lifecycleInvocation(
                        ToolInvocation.Status.APPROVED,
                        "proposal-2", null, null, null
                )
        );

        ToolInvocation running = lifecycleInvocation(
                ToolInvocation.Status.RUNNING, "", T1, null, null
        );
        assertInvocationProgressionRejected(
                running,
                lifecycleInvocation(
                        ToolInvocation.Status.FAILED,
                        "", null, T2, failedOutcome(T2)
                )
        );
        assertInvocationProgressionRejected(
                running,
                lifecycleInvocation(
                        ToolInvocation.Status.RUNNING,
                        "", T2, null, null
                )
        );

        ToolInvocation failed = lifecycleInvocation(
                ToolInvocation.Status.FAILED, "", T1, T2, failedOutcome(T2)
        );
        assertInvocationProgressionRejected(
                failed,
                lifecycleInvocation(
                        ToolInvocation.Status.FAILED,
                        "", T1, T3, failedOutcome(T3)
                )
        );
    }

    @Test
    void transitionsPreserveUsageAndAllowInvocationAppend() {
        ToolInvocation first = invocation("inv-1", "run-1", 1);
        ToolInvocation second = invocation("inv-2", "run-1", 1);
        RunState running = state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(first, second), null, null, Map.of("tokens", 10L), null
        );

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 4, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                        List.of(first, second), null, null, Map.of(), null
                )
        )).hasMessageContaining("usage");

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 4, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                        List.of(first, second), null, null, Map.of("tokens", 9L), null
                )
        )).hasMessageContaining("usage");

        ToolInvocation third = invocation("inv-3", "run-1", 1);
        RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 4, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                        List.of(first, second, third), null, null,
                        Map.of("tokens", 10L, "tools", 1L), null
                )
        );
    }

    @Test
    void appendedInvocationMustBelongToCurrentAttempt() {
        ToolInvocation previousAttempt = invocation("inv-1", "run-1", 1);
        RunState running = state(
                "run-1", "run-1", 6, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-2", 2, "",
                List.of(previousAttempt), null, null, Map.of(), null
        );
        ToolInvocation staleAppend = invocation("inv-2", "run-1", 1);

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                running,
                nextState(
                        running, 7, RunStatus.VERIFYING, T3, null,
                        snapshot("run-1"), decision("run-1"), "strategy-2", 2, "",
                        List.of(previousAttempt, staleAppend), null, null, Map.of(), null
                )
        )).hasMessageContaining("attempt");
    }

    @Test
    void transitionsPreserveAttemptExceptVerificationRetry() {
        RunState changedDuringContextAssembly = state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-1"), null, "", 2, "", List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                createdState(),
                changedDuringContextAssembly
        )).hasMessageContaining("attempt");

        RunState changedDuringExecution = state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 2, "",
                List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                runningState(),
                changedDuringExecution
        )).hasMessageContaining("attempt");

        RunState waiting = state(
                "run-1", "run-1", 4, RunStatus.WAITING_CONFIRMATION, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "proposal-1",
                List.of(), null, null, null
        );
        RunState resumedWithChangedAttempt = state(
                "run-1", "run-1", 5, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 2, "",
                List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                waiting,
                resumedWithChangedAttempt
        )).hasMessageContaining("attempt");
    }

    @Test
    void verifyingRetryRequiresRetryDecisionAndMatchingNextAttempt() {
        RunState retrying = verifyingState(1, null);
        RunState nextAttempt = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 2, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.RETRY, 2),
                null, null
        );
        assertThatCode(() -> RunTransitionPolicy.validate(retrying, nextAttempt))
                .doesNotThrowAnyException();

        RunState missingDecision = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 2, "",
                List.of(), null, null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(retrying, missingDecision))
                .hasMessageContaining("RETRY");

        RunState wrongOutcome = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 2, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(retrying, wrongOutcome))
                .hasMessageContaining("RETRY");

        RunState skippedAttempt = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 3, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.RETRY, 3),
                null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(retrying, skippedAttempt))
                .hasMessageContaining("attempt");

        RunState mismatchedDecisionAttempt = state(
                "run-1", "run-1", 5, RunStatus.DECIDED, T3, null,
                snapshot("run-1"), decision("run-1", "retry-goal"), "", 2, "",
                List.of(), completion("run-1", CompletionDecision.Outcome.RETRY, 3),
                null, null
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(retrying, mismatchedDecisionAttempt))
                .hasMessageContaining("nextAttempt");

        RunState replannedRunning = nextState(
                nextAttempt, 6, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), nextAttempt.executionDecision(), "strategy-2", 2, "",
                List.of(), nextAttempt.completionDecision(), null, Map.of(), null
        );
        RunTransitionPolicy.validate(nextAttempt, replannedRunning);
    }

    @Test
    void verifyingFailureRequiresFailCompletionDecision() {
        RunState verifying = verifyingState(1, null);
        RunState failedWithoutDecision = terminalState(
                RunStatus.FAILED,
                null,
                null,
                failure()
        );
        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                verifying,
                failedWithoutDecision
        )).hasMessageContaining("FAIL");

        RunState failedWithDecision = terminalState(
                RunStatus.FAILED,
                completion("run-1", CompletionDecision.Outcome.FAIL, 0),
                null,
                failure()
        );
        RunTransitionPolicy.validate(verifying, failedWithDecision);
    }

    @Test
    void runningToVerifyingAndConfirmationResumeNeedNoInventedEvidence() {
        RunState running = runningState();
        RunState verifying = state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        );
        RunTransitionPolicy.validate(running, verifying);

        RunState waiting = state(
                "run-1", "run-1", 4, RunStatus.WAITING_CONFIRMATION, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "proposal-1",
                List.of(), null, null, null
        );
        RunState resumed = state(
                "run-1", "run-1", 5, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        );
        RunTransitionPolicy.validate(waiting, resumed);
    }

    @Test
    void failedTransitionRequiresFailureAndTerminalStatesRemainImmutable() {
        RunState failed = state(
                "run-1", "run-1", 1, RunStatus.FAILED, T1, T1,
                null, null, "", 1, "", List.of(), null, null, failure()
        );
        RunTransitionPolicy.validate(createdState(), failed);

        assertThatThrownBy(() -> RunTransitionPolicy.validate(
                completedState(),
                completedState()
        )).hasMessageContaining("terminal");
    }

    @Test
    void terminalRunStateSurvivesJacksonRoundTrip() throws Exception {
        RunState completed = completedState();
        String json = mapper.writeValueAsString(completed);
        RunState deserialized = mapper.readValue(json, RunState.class);
        assertThat(deserialized).isEqualTo(completed);
    }

    @Test
    void failedRunWithResultAndCompletedInvocationSurvivesJacksonRoundTrip() throws Exception {
        ToolInvocation completedInvocation = completedInvocation(
                "inv-1", "run-1", 1, T1, T2
        );
        RunState failed = state(
                "run-1", "run-1", 5, RunStatus.FAILED, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(completedInvocation),
                completion("run-1", CompletionDecision.Outcome.FAIL, 0),
                result("run-1", RunStatus.FAILED, T3, "failed"),
                Map.of("tokens", 12L),
                failure()
        );

        String json = mapper.writeValueAsString(failed);
        RunState deserialized = mapper.readValue(json, RunState.class);

        assertThat(deserialized).isEqualTo(failed);
        assertThat(deserialized.toolInvocations()).containsExactly(completedInvocation);
        assertThat(deserialized.result().failureCode()).isEqualTo(failed.failure().code());
    }

    private static RunState createdState() {
        return state(
                "run-1", "run-1", 0, RunStatus.CREATED, T0, null,
                null, null, "", 1, "", List.of(), null, null, null
        );
    }

    private static RunState contextReadyState() {
        return state(
                "run-1", "run-1", 1, RunStatus.CONTEXT_READY, T1, null,
                snapshot("run-1"), null, "", 1, "", List.of(), null, null, null
        );
    }

    private static RunState decidedState() {
        return state(
                "run-1", "run-1", 2, RunStatus.DECIDED, T2, null,
                snapshot("run-1"), decision("run-1"), "", 1, "",
                List.of(), null, null, null
        );
    }

    private static RunState runningState() {
        return state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), null, null, null
        );
    }

    private static RunState verifyingState(int attempt, CompletionDecision completionDecision) {
        return state(
                "run-1", "run-1", 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", attempt, "",
                List.of(), completionDecision, null, null
        );
    }

    private static RunState completedState() {
        return terminalState(
                RunStatus.COMPLETED,
                completion("run-1", CompletionDecision.Outcome.COMPLETE, 0),
                result("run-1", RunStatus.COMPLETED),
                null
        );
    }

    private static RunState terminalState(
            RunStatus status,
            CompletionDecision completionDecision,
            RunResult result,
            RunState.Failure failure
    ) {
        return state(
                "run-1", "run-1", 5, status, T3, T3,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(), completionDecision, result, failure
        );
    }

    private static RunState state(
            String runId,
            String requestId,
            long revision,
            RunStatus status,
            Instant updatedAt,
            Instant finishedAt,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision,
            String strategyId,
            int attempt,
            String pendingProposalId,
            List<ToolInvocation> toolInvocations,
            CompletionDecision completionDecision,
            RunResult result,
            RunState.Failure failure
    ) {
        return state(
                runId, requestId, revision, status, updatedAt, finishedAt,
                contextSnapshot, executionDecision, strategyId, attempt,
                pendingProposalId, toolInvocations, completionDecision, result,
                Map.of(), failure
        );
    }

    private static RunState state(
            String runId,
            String requestId,
            long revision,
            RunStatus status,
            Instant updatedAt,
            Instant finishedAt,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision,
            String strategyId,
            int attempt,
            String pendingProposalId,
            List<ToolInvocation> toolInvocations,
            CompletionDecision completionDecision,
            RunResult result,
            Map<String, Long> usage,
            RunState.Failure failure
    ) {
        return new RunState(
                runId,
                requestId,
                revision,
                status,
                "session-1",
                "web",
                "user-1",
                personalClaim("web", "session-1", "user-1"),
                "USER",
                "hello",
                "agent",
                T0,
                status == RunStatus.CREATED ? null : T1,
                updatedAt,
                finishedAt,
                T3.plusSeconds(30),
                contextSnapshot,
                executionDecision,
                strategyId,
                attempt,
                pendingProposalId,
                toolInvocations,
                completionDecision,
                result,
                usage,
                failure,
                null
        );
    }

    private static RunState nextState(
            RunState previous,
            long revision,
            RunStatus status,
            Instant updatedAt,
            Instant finishedAt,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision,
            String strategyId,
            int attempt,
            String pendingProposalId,
            List<ToolInvocation> toolInvocations,
            CompletionDecision completionDecision,
            RunResult result,
            Map<String, Long> usage,
            RunState.Failure failure
    ) {
        Instant startedAt = previous.startedAt() == null && status != RunStatus.CREATED
                ? updatedAt
                : previous.startedAt();
        return new RunState(
                previous.runId(),
                previous.requestId(),
                revision,
                status,
                previous.sessionKey(),
                previous.channel(),
                previous.userId(),
                previous.sessionAccessClaim(),
                previous.roleCodeAtAcceptance(),
                previous.originalMessage(),
                previous.responseMode(),
                previous.acceptedAt(),
                startedAt,
                updatedAt,
                finishedAt,
                previous.deadlineAt(),
                contextSnapshot,
                executionDecision,
                strategyId,
                attempt,
                pendingProposalId,
                toolInvocations,
                completionDecision,
                result,
                usage,
                failure,
                null
        );
    }

    private static RunState stateWithStartedAt(
            RunState previous,
            long revision,
            RunStatus status,
            Instant updatedAt,
            Instant finishedAt,
            Instant startedAt,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision,
            String strategyId,
            int attempt,
            String pendingProposalId,
            List<ToolInvocation> toolInvocations,
            CompletionDecision completionDecision,
            RunResult result,
            Map<String, Long> usage,
            RunState.Failure failure
    ) {
        return new RunState(
                previous.runId(),
                previous.requestId(),
                revision,
                status,
                previous.sessionKey(),
                previous.channel(),
                previous.userId(),
                previous.sessionAccessClaim(),
                previous.roleCodeAtAcceptance(),
                previous.originalMessage(),
                previous.responseMode(),
                previous.acceptedAt(),
                startedAt,
                updatedAt,
                finishedAt,
                previous.deadlineAt(),
                contextSnapshot,
                executionDecision,
                strategyId,
                attempt,
                pendingProposalId,
                toolInvocations,
                completionDecision,
                result,
                usage,
                failure,
                null
        );
    }

    private static RunState acceptanceVariant(
            RunState source,
            String sessionKey,
            String channel,
            String userId,
            String roleCodeAtAcceptance,
            String originalMessage,
            String responseMode,
            Instant acceptedAt,
            Instant deadlineAt
    ) {
        return new RunState(
                source.runId(),
                source.requestId(),
                source.revision(),
                source.status(),
                sessionKey,
                channel,
                userId,
                source.sessionAccessClaim(),
                roleCodeAtAcceptance,
                originalMessage,
                responseMode,
                acceptedAt,
                source.startedAt(),
                source.updatedAt(),
                source.finishedAt(),
                deadlineAt,
                source.contextSnapshot(),
                source.executionDecision(),
                source.strategyId(),
                source.attempt(),
                source.pendingProposalId(),
                source.toolInvocations(),
                source.completionDecision(),
                source.result(),
                source.usage(),
                source.failure(),
                null
        );
    }

    private static RunState claimVariant(
            RunState source,
            SessionAccessClaim sessionAccessClaim
    ) {
        return new RunState(
                source.runId(),
                source.requestId(),
                source.revision(),
                source.status(),
                source.sessionKey(),
                source.channel(),
                source.userId(),
                sessionAccessClaim,
                source.roleCodeAtAcceptance(),
                source.originalMessage(),
                source.responseMode(),
                source.acceptedAt(),
                source.startedAt(),
                source.updatedAt(),
                source.finishedAt(),
                source.deadlineAt(),
                source.contextSnapshot(),
                source.executionDecision(),
                source.strategyId(),
                source.attempt(),
                source.pendingProposalId(),
                source.toolInvocations(),
                source.completionDecision(),
                source.result(),
                source.usage(),
                source.failure(),
                null
        );
    }

    private static RunState identityVariant(
            RunState source,
            String sessionKey,
            String channel,
            String userId
    ) {
        return new RunState(
                source.runId(),
                source.requestId(),
                source.revision(),
                source.status(),
                sessionKey,
                channel,
                userId,
                source.sessionAccessClaim(),
                source.roleCodeAtAcceptance(),
                source.originalMessage(),
                source.responseMode(),
                source.acceptedAt(),
                source.startedAt(),
                source.updatedAt(),
                source.finishedAt(),
                source.deadlineAt(),
                source.contextSnapshot(),
                source.executionDecision(),
                source.strategyId(),
                source.attempt(),
                source.pendingProposalId(),
                source.toolInvocations(),
                source.completionDecision(),
                source.result(),
                source.usage(),
                source.failure(),
                null
        );
    }

    private static RunState runningAt(
            Instant acceptedAt,
            Instant startedAt,
            Instant updatedAt,
            Instant finishedAt,
            Instant deadlineAt,
            RunState.Failure failure
    ) {
        return new RunState(
                "run-1",
                "run-1",
                3,
                RunStatus.RUNNING,
                "session-1",
                "web",
                "user-1",
                personalClaim("web", "session-1", "user-1"),
                "USER",
                "hello",
                "agent",
                acceptedAt,
                startedAt,
                updatedAt,
                finishedAt,
                deadlineAt,
                snapshot("run-1"),
                decision("run-1"),
                "strategy-1",
                1,
                "",
                List.of(),
                null,
                null,
                Map.of(),
                failure,
                null
        );
    }

    private static ContextSnapshot snapshot(String runId) {
        return snapshot(runId, "hash-1");
    }

    private static SessionAccessClaim personalClaim(
            String channel,
            String sessionKey,
            String userId
    ) {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                channel,
                sessionKey,
                userId
        );
    }

    private static ContextSnapshot snapshot(String runId, String snapshotHash) {
        return new ContextSnapshot(
                runId, "session-1", "user-1", "web", "user-1", "USER",
                "original", "effective", "system", "memory",
                List.of(), List.of(), List.of(), List.of("web.search"),
                Map.of(), Map.of("schema", "v1"), memoryFrame(runId), T0, snapshotHash
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
                Map.of("source", "legacy-test"), List.of(),
                java.time.Instant.parse("2026-06-24T00:00:00Z"), "frame-hash-" + runId
        );
    }

    private static ExecutionDecision decision(String runId) {
        return decision(runId, "answer");
    }

    private static ExecutionDecision decision(String runId, String goal) {
        return new ExecutionDecision(
                runId, "research", goal, "agent", "read",
                List.of("web.search"), List.of(), Map.of(), List.of(),
                0.8, "matched capability", "policy", T0
        );
    }

    private static ToolInvocation invocation(String runId) {
        return invocation("inv-1", runId, 1);
    }

    private static ToolInvocation invocation(String invocationId, String runId, int attempt) {
        return new ToolInvocation(
                invocationId, runId, attempt, "web.search", "search",
                "search", "web", "{}", "hash", ToolInvocation.RiskLevel.READ,
                List.of(), List.of(), runId + ":" + attempt + ":" + invocationId,
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );
    }

    private static ToolInvocation invocationWithIdempotencyKey(
            String invocationId,
            String idempotencyKey,
            int attempt
    ) {
        return new ToolInvocation(
                invocationId, "run-1", attempt, "web.search", "search",
                "search", "web", "{}", "hash", ToolInvocation.RiskLevel.READ,
                List.of(), List.of(), idempotencyKey,
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );
    }

    private static ToolInvocation invocationWithCapability(
            String invocationId,
            String runId,
            int attempt,
            String capabilityId
    ) {
        return new ToolInvocation(
                invocationId, runId, attempt, capabilityId, "search",
                "search", "web", "{}", "hash", ToolInvocation.RiskLevel.READ,
                List.of(), List.of(), runId + ":" + attempt + ":" + invocationId,
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );
    }

    private static ToolInvocation lifecycleInvocation(
            ToolInvocation.Status status,
            String proposalId,
            Instant startedAt,
            Instant finishedAt,
            ToolInvocation.Outcome outcome
    ) {
        return new ToolInvocation(
                "inv-lifecycle",
                "run-1",
                1,
                "web.search",
                "search",
                "search",
                "web",
                "{}",
                "hash",
                ToolInvocation.RiskLevel.READ,
                List.of(),
                List.of("search-result"),
                "run-1:1:inv-lifecycle",
                status,
                proposalId,
                startedAt,
                finishedAt,
                outcome
        );
    }

    private static ToolInvocation.Outcome successfulOutcome(Instant completedAt) {
        return new ToolInvocation.Outcome(
                true, "ok", "completed", List.of("search-result"), completedAt
        );
    }

    private static ToolInvocation.Outcome failedOutcome(Instant completedAt) {
        return new ToolInvocation.Outcome(
                false, "failed", "failed", List.of("failure"), completedAt
        );
    }

    private static void assertInvocationProgressionAccepted(
            ToolInvocation previousInvocation,
            ToolInvocation nextInvocation
    ) {
        RunState previous = state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(previousInvocation), null, null, Map.of(), null
        );
        RunState next = nextState(
                previous, 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(nextInvocation), null, null, Map.of(), null
        );

        assertThatCode(() -> RunTransitionPolicy.validate(previous, next))
                .doesNotThrowAnyException();
    }

    private static void assertInvocationProgressionRejected(
            ToolInvocation previousInvocation,
            ToolInvocation nextInvocation
    ) {
        RunState previous = state(
                "run-1", "run-1", 3, RunStatus.RUNNING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(previousInvocation), null, null, Map.of(), null
        );
        RunState next = nextState(
                previous, 4, RunStatus.VERIFYING, T3, null,
                snapshot("run-1"), decision("run-1"), "strategy-1", 1, "",
                List.of(nextInvocation), null, null, Map.of(), null
        );

        assertThatThrownBy(() -> RunTransitionPolicy.validate(previous, next))
                .hasMessageContaining("toolInvocations");
    }

    private static ToolInvocation completedInvocation(
            String invocationId,
            String runId,
            int attempt,
            Instant startedAt,
            Instant finishedAt
    ) {
        return new ToolInvocation(
                invocationId, runId, attempt, "web.search", "search",
                "search", "web", "{}", "hash", ToolInvocation.RiskLevel.READ,
                List.of(), List.of("search-result"),
                runId + ":" + attempt + ":" + invocationId,
                ToolInvocation.Status.SUCCEEDED, null, startedAt, finishedAt,
                new ToolInvocation.Outcome(
                        true, "ok", "completed", List.of("search-result"), finishedAt
                )
        );
    }

    private static CompletionDecision completion(
            String runId,
            CompletionDecision.Outcome outcome,
            int nextAttempt
    ) {
        return new CompletionDecision(
                runId, outcome, outcome.name().toLowerCase(), "summary",
                List.of(), List.of(), outcome == CompletionDecision.Outcome.RETRY,
                nextAttempt, 1.0, T3
        );
    }

    private static RunResult result(String runId, RunStatus status) {
        return result(
                runId,
                status,
                T3,
                status == RunStatus.FAILED ? "failed" : ""
        );
    }

    private static RunResult result(
            String runId,
            RunStatus status,
            Instant completedAt,
            String failureCode
    ) {
        RunResult.AnswerKind answerKind = switch (status) {
            case COMPLETED -> RunResult.AnswerKind.FINAL;
            case DEGRADED -> RunResult.AnswerKind.DEGRADED;
            case FAILED -> RunResult.AnswerKind.FAILURE;
            default -> throw new IllegalArgumentException("terminal status required");
        };
        return new RunResult(
                runId, status, "answer", answerKind,
                "provider", "model", List.of(), List.of(), 1.0,
                Map.of(), failureCode, null, completedAt
        );
    }

    private static RunState.Failure failure() {
        return new RunState.Failure("failed", "failure", false);
    }
}
