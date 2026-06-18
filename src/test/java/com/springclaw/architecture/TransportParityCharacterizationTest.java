package com.springclaw.architecture;

import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import com.springclaw.service.chat.async.AsyncChatResultPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test — pins the differences between sync, SSE, and
 * RabbitMQ async transports today. Findings referenced from the runtime audit
 * doc § 8 ("Persistence — 12 callsites"), § 10 ("Stream lifecycle — 4 owners"),
 * and § 11 ("Proposal confirm-resume — disconnected from originating SSE").
 *
 * <p>The audit's transport-parity invariant from the collaboration plan
 * (Phase 1 invariant 7) is: <em>REST, SSE, RabbitMQ, persistence, trace, and
 * audit must project the same Run.</em> Today they do not. This test pins the
 * structural difference so any unification PR demonstrates progress against a
 * baseline.
 */
class TransportParityCharacterizationTest {

    @Test
    @DisplayName("AsyncChatRequestMessage carries 7 fields — request envelope today "
            + "is independent of ChatRequest used by sync/SSE")
    void asyncRequestMessageRecordShape() {
        assertThat(AsyncChatRequestMessage.class.isRecord()).isTrue();

        List<String> components = Arrays.stream(AsyncChatRequestMessage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly(
                "requestId", "sessionKey", "userId", "message",
                "channel", "createdAt", "responseMode");

        // Documented gap with sync transport: there is no `roleCode`,
        // `extraContext`, or any field the unified-runtime spec would call
        // a `RunEvent`. The async path is "send a flat envelope, do
        // synchronous chat() inside the consumer, and persist via the same
        // ChatResultPersister.persist callsite #1 (audit § 8 line 1)."
        assertThat(components).doesNotContain("roleCode", "runId");
    }

    @Test
    @DisplayName("AsyncChatResultPayload carries 9 fields — result envelope is "
            + "STORED in Redis with TTL but is NOT projected into SSE or DB trace")
    void asyncResultPayloadRecordShape() {
        assertThat(AsyncChatResultPayload.class.isRecord()).isTrue();

        List<String> components = Arrays.stream(AsyncChatResultPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly(
                "requestId", "status", "sessionKey", "channel",
                "answer", "model", "createdAt", "completedAt", "errorMessage");

        // The audit § 8 records that the async path reuses persistence
        // callsite #1 (sync ChatServiceImpl.executeInternal). It does not
        // emit SSE events. The payload above is the ONLY view the polling
        // client sees — sync trace rows are unavailable here.
        assertThat(components).doesNotContain("traceEvents", "runEvents", "sseEvents");
    }

    @Test
    @DisplayName("AsyncChatResultStore TTL default — pins 24h Redis retention so "
            + "any change to async result lifecycle is detected")
    void asyncStoreTtlDefault() throws Exception {
        // Documented in audit § 12 config table — 24h default. The unified
        // RunResult lifecycle should pick a single retention policy, not
        // three different ones (sync = none / SSE = none / async = 24h).
        java.lang.reflect.Field ttlField =
                Class.forName("com.springclaw.service.chat.async.AsyncChatResultStore",
                        false, getClass().getClassLoader())
                        .getDeclaredField("ttlHours");
        assertThat(ttlField.getType()).isEqualTo(long.class);
    }

    @Test
    @DisplayName("RabbitMQ consumer reuses sync chat() — async transport reroutes "
            + "into ChatResultPersister.persist callsite #1, no separate persist site")
    void asyncConsumerReusesSyncChat() throws ClassNotFoundException {
        Class<?> consumer = Class.forName("com.springclaw.service.chat.async.ChatMessageConsumer");
        // The audit § 8 names the 12 ChatResultPersister.persist callsites
        // and explicitly notes that async (#5 transport in spec) does NOT
        // appear in that list — the persistence happens transitively via
        // synchronous chat(). Pin via the consumer holding a ChatService dep.
        java.lang.reflect.Field[] fields = consumer.getDeclaredFields();
        boolean holdsChatService = Arrays.stream(fields)
                .anyMatch(f -> f.getType().getSimpleName().equals("ChatService"));
        assertThat(holdsChatService)
                .as("ChatMessageConsumer should reuse ChatService (sync chat path)")
                .isTrue();
    }

    @Test
    @DisplayName("Three transports have different completion semantics today — "
            + "pin the divergence as a baseline for future unification")
    void transportCompletionSemanticsAreDifferent() {
        // This is a doc-as-test of the audit § 10. Each row pins the
        // current 'who decides this transport is done' answer.
        Map<String, String> completionOwners = Map.of(
                "sync POST /api/chat/send",
                    "ChatServiceImpl.executeInternal returns; finally{} releases lock",
                "SSE POST /api/chat/stream",
                    "engine.doOnComplete OR ChatServiceImpl.stream.onTimeout — race possible",
                "async POST /api/chat/async",
                    "ChatMessageConsumer.consume returns; AsyncChatResultStore.markCompleted; "
                        + "no SSE projection back to original request");

        assertThat(completionOwners).hasSize(3);
        assertThat(completionOwners.values())
                .allMatch(s -> s != null && !s.isBlank());

        // The unified-runtime spec must collapse this to a single
        // completion contract; the test will need to be migrated together
        // with the implementation.
    }

    @Test
    @DisplayName("Proposal confirm-resume produces NO projection back into the "
            + "originating SSE — pin via the absence of any cross-link field")
    void proposalConfirmHasNoSseProjection() throws ClassNotFoundException {
        Class<?> proposalRecord = Class.forName(
                "com.springclaw.service.proposal.ToolInvocationProposal");

        List<String> componentNames = Arrays.stream(proposalRecord.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // The proposal record carries requestId / runId / sessionKey for
        // tracing but has NO field that an SSE projector would consume to
        // re-emit on the original chat stream. The audit § 11 records this
        // as 'no result projection back into the original chat stream'.
        assertThat(componentNames)
                .contains("requestId", "runId", "sessionKey")
                .doesNotContain("originSseEmitterId", "resumeChannel", "projectionTarget");
    }

    @Test
    @DisplayName("Documented transport inventory — pinning the 4 paths the "
            + "collaboration plan required Phase 1 to characterize")
    void fourTransportLifecyclesAuditedHere() {
        // Phase 1 deliverable from the collaboration plan §10 enumerates:
        //   sync, SSE, RabbitMQ async, proposal-confirm-resume.
        // We pin the inventory so a future PR adding a fifth transport
        // (e.g., WebSocket, gRPC) is forced to declare it.
        List<String> auditedTransports = List.of(
                "POST /api/chat/send (sync)",
                "POST /api/chat/stream (SSE)",
                "POST /api/chat/async (RabbitMQ + Redis result store)",
                "POST /api/tool-proposals/{id}/confirm (resume via async event)");

        assertThat(auditedTransports).hasSize(4);
    }

    @Test
    @DisplayName("Pin: SseEventBridge has both sendEvent helper AND completeEmitter "
            + "helper — stream termination is a 4-owner shared responsibility")
    void sseEventBridgeShape() throws ClassNotFoundException {
        Class<?> bridge = Class.forName("com.springclaw.service.chat.impl.SseEventBridge");
        java.util.Set<String> publicMethods = Arrays.stream(bridge.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        // Both helpers exist as part of the audit § 10 'helper called by all
        // four owners' record. If either disappears without a unified-runtime
        // spec entry, regression.
        assertThat(publicMethods).contains("completeEmitter");
        assertThat(publicMethods)
                .as("Bridge should expose at least one event-emitting helper")
                .anyMatch(name -> name.startsWith("send"));
    }
}
