package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunCoordinatorTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-21T00:00:00Z");

    private final InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
    private final RunCoordinator coordinator = new RunCoordinator(store);

    @Test
    void coordinatesAcceptedRunThroughConfirmationAndCompletion() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.waitingConfirmation(RUN_ID, "proposal-1", T0.plusSeconds(4));
        coordinator.confirmationApproved(RUN_ID, T0.plusSeconds(5));
        coordinator.verifying(RUN_ID, T0.plusSeconds(6));
        coordinator.completed(
                RUN_ID,
                completion(CompletionDecision.Outcome.COMPLETE, T0.plusSeconds(7)),
                result(RunStatus.COMPLETED, T0.plusSeconds(7)),
                T0.plusSeconds(7)
        );

        RunState state = store.requireByRunId(RUN_ID);
        assertThat(state.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.revision()).isEqualTo(7);
        assertThat(state.sessionAccessClaim()).isEqualTo(acceptance().sessionAccessClaim());
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.CONFIRMATION_REQUIRED,
                        RunEventType.CONFIRMATION_APPROVED,
                        RunEventType.VERIFICATION_STARTED,
                        RunEventType.RUN_COMPLETED
                );
    }

    @Test
    void coordinatesDegradedAndFailedTerminalOutcomes() {
        prepareVerifyingRun();
        coordinator.degraded(
                RUN_ID,
                completion(CompletionDecision.Outcome.DEGRADE, T0.plusSeconds(5)),
                result(RunStatus.DEGRADED, T0.plusSeconds(5)),
                T0.plusSeconds(5)
        );
        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.DEGRADED);

        String failedRunId = "11111111111111111111111111111111";
        coordinator.accept(acceptance(failedRunId));
        coordinator.failed(
                failedRunId,
                new RunState.Failure("CONTEXT_FAILED", "context unavailable", true),
                T0.plusSeconds(1)
        );
        assertThat(store.requireByRunId(failedRunId).status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void verifyingFailureRequiresAndPersistsFailCompletionDecision() {
        prepareVerifyingRun();
        CompletionDecision failureDecision = completion(
                CompletionDecision.Outcome.FAIL,
                T0.plusSeconds(5)
        );

        coordinator.failed(
                RUN_ID,
                failureDecision,
                new RunState.Failure("VERIFICATION_FAILED", "insufficient evidence", false),
                T0.plusSeconds(5)
        );

        RunState failed = store.requireByRunId(RUN_ID);
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.completionDecision()).isEqualTo(failureDecision);
    }

    @Test
    void rejectsMutationAfterTerminalState() {
        String runId = "22222222222222222222222222222222";
        coordinator.accept(acceptance(runId));
        coordinator.failed(
                runId,
                new RunState.Failure("REJECTED", "rejected", false),
                T0.plusSeconds(1)
        );

        assertThatThrownBy(() -> coordinator.failed(
                runId,
                new RunState.Failure("SECOND", "second", false),
                T0.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void appendsToolFactsWithoutChangingStateRevision() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        RunState running = coordinator.running(
                RUN_ID, "agent-runtime", T0.plusSeconds(3)
        );

        coordinator.toolStarted(RUN_ID, T0.plusSeconds(4));
        coordinator.toolSucceeded(RUN_ID, T0.plusSeconds(5));

        assertThat(store.requireByRunId(RUN_ID)).isEqualTo(running);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .endsWith(RunEventType.TOOL_STARTED, RunEventType.TOOL_SUCCEEDED);
    }

    @Test
    void appendsModelCalledObservationWithoutChangingState() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        RunState running = coordinator.running(
                RUN_ID, "agent-runtime", T0.plusSeconds(3)
        );

        RunEvent emitted = coordinator.modelCalled(RUN_ID, T0.plusSeconds(4));

        assertThat(emitted.eventType()).isEqualTo(RunEventType.MODEL_CALLED);
        assertThat(store.requireByRunId(RUN_ID)).isEqualTo(running);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .endsWith(RunEventType.MODEL_CALLED);
    }

    @Test
    void appendsMemoryObservationsAfterTerminal() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.verifying(RUN_ID, T0.plusSeconds(4));
        coordinator.completed(
                RUN_ID,
                completion(CompletionDecision.Outcome.COMPLETE, T0.plusSeconds(5)),
                result(RunStatus.COMPLETED, T0.plusSeconds(5)),
                T0.plusSeconds(5)
        );

        RunEvent extracted = coordinator.memoryExtracted(RUN_ID, T0.plusSeconds(6));
        RunEvent reflected = coordinator.reflected(RUN_ID, T0.plusSeconds(7));

        assertThat(extracted.eventType()).isEqualTo(RunEventType.MEMORY_EXTRACTED);
        assertThat(reflected.eventType()).isEqualTo(RunEventType.REFLECTED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .endsWith(RunEventType.RUN_COMPLETED, RunEventType.MEMORY_EXTRACTED, RunEventType.REFLECTED);
    }

    @Test
    void confirmationRejectionFailsRunWithTypedBoundaryEvent() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.waitingConfirmation(RUN_ID, "proposal-1", T0.plusSeconds(4));

        coordinator.confirmationRejected(
                RUN_ID,
                new RunState.Failure(
                        "CONFIRMATION_REJECTED", "rejected by user", false
                ),
                T0.plusSeconds(5)
        );

        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.FAILED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .endsWith(RunEventType.CONFIRMATION_REJECTED);
    }

    @Test
    void confirmationRejectionCannotTerminateRunThatIsNotWaiting() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));

        assertThatThrownBy(() -> coordinator.confirmationRejected(
                RUN_ID,
                new RunState.Failure(
                        "CONFIRMATION_REJECTED", "stale rejection", false
                ),
                T0.plusSeconds(4)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITING_CONFIRMATION");
        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void confirmationApprovalCannotResumeRunThatIsNotWaiting() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));

        assertThatThrownBy(() -> coordinator.confirmationApproved(
                RUN_ID,
                T0.plusSeconds(3)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITING_CONFIRMATION");
        assertThat(store.requireByRunId(RUN_ID).status())
                .isEqualTo(RunStatus.DECIDED);
    }

    @Test
    void acceptanceRejectsSessionAccessClaimMismatch() {
        assertThatThrownBy(() -> new RunAcceptance(
                RUN_ID,
                "session-1",
                "api",
                "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "web",
                        "session-1",
                        "user-1"
                ),
                "USER",
                "hello",
                "agent",
                T0,
                T0.plusSeconds(300),
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void acceptanceNormalizesIdentityBeforeComparingClaim() {
        RunAcceptance acceptance = new RunAcceptance(
                RUN_ID,
                " session-1 ",
                " api ",
                " user-1 ",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                ),
                "USER",
                "hello",
                "agent",
                T0,
                T0.plusSeconds(300),
                null
        );

        assertThat(acceptance.sessionKey()).isEqualTo("session-1");
        assertThat(acceptance.channel()).isEqualTo("api");
        assertThat(acceptance.userId()).isEqualTo("user-1");
    }

    @Test
    void acceptRecordsParadigmFromAcceptance() {
        RunState state = coordinator.accept(acceptance(AgentParadigm.OPAR));
        assertThat(state.paradigm()).isEqualTo(AgentParadigm.OPAR);
    }

    @Test
    void acceptAllowsNullParadigmForWebhookAndTaskPaths() {
        RunState state = coordinator.accept(acceptance());
        assertThat(state.paradigm()).isNull();
    }

    @Test
    void eventsCarryParadigmFromState() {
        coordinator.accept(acceptance(RUN_ID, AgentParadigm.OPAR));
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        // event() 是单点:RUN_CREATED + CONTEXT_READY 全部事件都应带 OPAR
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::paradigm)
                .allMatch(p -> p == AgentParadigm.OPAR);
    }

    @Test
    void modelCalledCarriesProviderAndModelPayload() {
        coordinator.accept(acceptance());
        RunEvent emitted = coordinator.modelCalled(
                RUN_ID, "primary", "deepseek-chat", T0.plusSeconds(1)
        );

        assertThat(emitted.eventType()).isEqualTo(RunEventType.MODEL_CALLED);
        assertThat(emitted.payloadSchema())
                .isEqualTo("springclaw.runtime.observation.v1");
        assertThat(emitted.payload()).contains("\"providerId\":\"primary\"");
        assertThat(emitted.payload()).contains("\"model\":\"deepseek-chat\"");
    }

    @Test
    void toolObservationsCarryStructuredPayload() {
        coordinator.accept(acceptance());
        coordinator.toolStarted(RUN_ID, "SystemToolPack.runCommand", T0.plusSeconds(1));
        coordinator.toolSucceeded(
                RUN_ID, "SystemToolPack.runCommand", 42L, T0.plusSeconds(2)
        );
        coordinator.toolFailed(RUN_ID, "WebToolPack.fetch", "TIMEOUT", T0.plusSeconds(3));

        List<RunEvent> events = store.findEventsByRunId(RUN_ID);
        assertThat(events).extracting(RunEvent::eventType).containsExactly(
                RunEventType.RUN_CREATED,
                RunEventType.TOOL_STARTED,
                RunEventType.TOOL_SUCCEEDED,
                RunEventType.TOOL_FAILED
        );
        assertThat(events.get(1).payload())
                .contains("\"toolName\":\"SystemToolPack.runCommand\"");
        assertThat(events.get(2).payload())
                .contains("\"toolName\":\"SystemToolPack.runCommand\"")
                .contains("\"durationMs\":42");
        assertThat(events.get(3).payload())
                .contains("\"toolName\":\"WebToolPack.fetch\"")
                .contains("\"errorCode\":\"TIMEOUT\"");
        assertThat(events.get(2).durationMs()).isEqualTo(42L);
    }

    @Test
    void turnAndStepBoundariesAreObservationsSurvivingTerminalState() {
        // 走完整状态机链(CREATED→CONTEXT_READY→DECIDED→RUNNING→VERIFYING→COMPLETED),
        // turn/step observation 夹在中间与终态之后——它们不参与迁移、不占 revision。
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.turnStarted(RUN_ID, "blocking", T0.plusSeconds(4));
        coordinator.stepStarted(RUN_ID, 0, "react", T0.plusSeconds(5));
        coordinator.stepCompleted(RUN_ID, 0, "react", "ok", 17L, T0.plusSeconds(6));
        coordinator.verifying(RUN_ID, T0.plusSeconds(7));
        coordinator.completed(
                RUN_ID,
                completion(CompletionDecision.Outcome.COMPLETE, T0.plusSeconds(8)),
                result(RunStatus.COMPLETED, T0.plusSeconds(8)),
                T0.plusSeconds(8)
        );
        // 终态后追加 turn.completed(observation 允许,不改状态机)
        coordinator.turnCompleted(RUN_ID, "COMPLETE", 120L, T0.plusSeconds(9));

        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsSubsequence(
                        RunEventType.RUN_CREATED,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.TURN_STARTED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.VERIFICATION_STARTED,
                        RunEventType.RUN_COMPLETED,
                        RunEventType.TURN_COMPLETED
                );
        List<RunEvent> events = store.findEventsByRunId(RUN_ID);
        RunEvent turnStarted = events.stream()
                .filter(e -> e.eventType() == RunEventType.TURN_STARTED).findFirst().orElseThrow();
        RunEvent stepStarted = events.stream()
                .filter(e -> e.eventType() == RunEventType.STEP_STARTED).findFirst().orElseThrow();
        RunEvent stepCompleted = events.stream()
                .filter(e -> e.eventType() == RunEventType.STEP_COMPLETED).findFirst().orElseThrow();
        RunEvent turnCompleted = events.stream()
                .filter(e -> e.eventType() == RunEventType.TURN_COMPLETED).findFirst().orElseThrow();
        assertThat(turnStarted.payload()).contains("\"responseMode\":\"blocking\"");
        assertThat(stepStarted.payload())
                .contains("\"stepIndex\":0")
                .contains("\"stepKind\":\"react\"");
        assertThat(stepCompleted.payload())
                .contains("\"stepIndex\":0")
                .contains("\"stepKind\":\"react\"")
                .contains("\"outcome\":\"ok\"")
                .contains("\"durationMs\":17");
        assertThat(stepCompleted.durationMs()).isEqualTo(17L);
        assertThat(turnCompleted.payload())
                .contains("\"outcome\":\"COMPLETE\"")
                .contains("\"durationMs\":120");
        assertThat(turnCompleted.durationMs()).isEqualTo(120L);
    }

    @Test
    void conversationSemanticsEventsCarryQuestionAndAnswerPayload() {
        // spec 2026-08-17-canonical-conversation-history §3.1:
        // user.message/assistant.answer 是 observation(不改状态机),payload 携带对话原文。
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.userMessage(RUN_ID, "分析当前项目架构", "blocking", T0.plusSeconds(3));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(4));
        coordinator.verifying(RUN_ID, T0.plusSeconds(5));
        coordinator.completed(
                RUN_ID,
                completion(CompletionDecision.Outcome.COMPLETE, T0.plusSeconds(6)),
                result(RunStatus.COMPLETED, T0.plusSeconds(6)),
                T0.plusSeconds(6)
        );
        coordinator.assistantAnswer(RUN_ID, "项目分层为 controller/service/tool", "FINAL", T0.plusSeconds(7));

        RunState state = store.requireByRunId(RUN_ID);
        assertThat(state.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsSubsequence(
                        RunEventType.RUN_CREATED,
                        RunEventType.USER_MESSAGE,
                        RunEventType.RUN_COMPLETED,
                        RunEventType.ASSISTANT_ANSWER
                );
        List<RunEvent> events = store.findEventsByRunId(RUN_ID);
        RunEvent userMessage = events.stream()
                .filter(e -> e.eventType() == RunEventType.USER_MESSAGE).findFirst().orElseThrow();
        RunEvent assistantAnswer = events.stream()
                .filter(e -> e.eventType() == RunEventType.ASSISTANT_ANSWER).findFirst().orElseThrow();
        assertThat(userMessage.payload())
                .contains("\"question\":\"分析当前项目架构\"")
                .contains("\"responseMode\":\"blocking\"");
        assertThat(assistantAnswer.payload())
                .contains("\"answer\":\"项目分层为 controller/service/tool\"")
                .contains("\"answerKind\":\"FINAL\"");
    }

    @Test
    void legacyNoPayloadOverloadsKeepLifecycleSchema() {
        coordinator.accept(acceptance());
        coordinator.modelCalled(RUN_ID, T0.plusSeconds(1));
        coordinator.toolStarted(RUN_ID, T0.plusSeconds(2));
        coordinator.toolSucceeded(RUN_ID, T0.plusSeconds(3));
        coordinator.toolFailed(RUN_ID, T0.plusSeconds(4));

        assertThat(store.findEventsByRunId(RUN_ID))
                .allSatisfy(event -> {
                    assertThat(event.payload()).isEqualTo("{}");
                    assertThat(event.payloadSchema())
                            .isEqualTo("springclaw.runtime.lifecycle.v1");
                });
    }

    private void prepareVerifyingRun() {
        coordinator.accept(acceptance());
        coordinator.contextReady(RUN_ID, snapshot(), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.verifying(RUN_ID, T0.plusSeconds(4));
    }

    private static RunAcceptance acceptance() {
        return acceptance(RUN_ID, null);
    }

    private static RunAcceptance acceptance(String runId) {
        return acceptance(runId, null);
    }

    private static RunAcceptance acceptance(AgentParadigm paradigm) {
        return acceptance(RUN_ID, paradigm);
    }

    private static RunAcceptance acceptance(String runId, AgentParadigm paradigm) {
        return new RunAcceptance(
                runId, "session-1", "api", "user-1",
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "user-1"
                ),
                "USER", "hello",
                "agent", T0, T0.plusSeconds(300),
                paradigm
        );
    }

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                RUN_ID, "session-1", "user-1", "api", "user-1", "USER",
                "hello", "hello", "system", "", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), memoryFrame(RUN_ID), T0.plusSeconds(1), "snapshot-hash"
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
                java.util.Map.of("source", "legacy-test"), List.of(),
                java.time.Instant.parse("2026-06-24T00:00:00Z"), "frame-hash-" + runId
        );
    }

    private static ExecutionDecision decision() {
        return new ExecutionDecision(
                RUN_ID, "general", "answer", "agent", "read", List.of(),
                List.of(), Map.of(), List.of(), 1.0, "legacy", "legacy",
                T0.plusSeconds(2)
        );
    }

    private static CompletionDecision completion(
            CompletionDecision.Outcome outcome,
            Instant at
    ) {
        return new CompletionDecision(
                RUN_ID, outcome, "LEGACY_" + outcome, "legacy", List.of("legacy"),
                List.of(), false, 0, 0.8, at
        );
    }

    private static RunResult result(RunStatus status, Instant at) {
        return new RunResult(
                RUN_ID, status, "answer",
                status == RunStatus.COMPLETED
                        ? RunResult.AnswerKind.FINAL
                        : RunResult.AnswerKind.DEGRADED,
                "legacy", "legacy", List.of("legacy"), List.of(), 0.8,
                Map.of(), "", "", at
        );
    }
}
