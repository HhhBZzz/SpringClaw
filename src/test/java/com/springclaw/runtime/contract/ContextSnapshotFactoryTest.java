package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextSnapshotFactoryTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void buildsSnapshotFromOneMemoryCoordinatorRetrieval() {
        MemoryCoordinator coordinator = mock(MemoryCoordinator.class);
        MemoryScope scope = scope();
        MemoryFrame frame = frame("run-1", "frame-hash-a");
        when(coordinator.retrieve(any(MemoryFrameRequest.class)))
                .thenReturn(new MemoryFrameResult(frame, trace("run-1", scope, "frame-hash-a")));

        ContextSnapshotFactory factory = new ContextSnapshotFactory(coordinator, CLOCK);
        ContextSnapshot snapshot = factory.create(request("run-1"));

        verify(coordinator).retrieve(new MemoryFrameRequest("run-1", scope, "effective"));
        assertThat(snapshot.runId()).isEqualTo("run-1");
        assertThat(snapshot.memoryFrame()).isEqualTo(frame);
        assertThat(snapshot.shortTermEvents()).isEmpty();
        assertThat(snapshot.contextSourceSummary())
                .containsEntry("memoryFrameHash", "frame-hash-a")
                .containsEntry("schema", "springclaw.context-snapshot.v1");
    }

    @Test
    void snapshotHashChangesWhenFrameHashChanges() {
        MemoryCoordinator coordinator = mock(MemoryCoordinator.class);
        ContextSnapshotFactory factory = new ContextSnapshotFactory(coordinator, CLOCK);
        when(coordinator.retrieve(any(MemoryFrameRequest.class)))
                .thenReturn(new MemoryFrameResult(frame("run-1", "frame-hash-a"), trace("run-1", scope(), "frame-hash-a")))
                .thenReturn(new MemoryFrameResult(frame("run-1", "frame-hash-b"), trace("run-1", scope(), "frame-hash-b")));

        ContextSnapshot first = factory.create(request("run-1"));
        ContextSnapshot second = factory.create(request("run-1"));

        assertThat(first.snapshotHash()).isNotEqualTo(second.snapshotHash());
    }

    private static ContextSnapshotRequest request(String runId) {
        SessionAccessClaim claim = SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
        return new ContextSnapshotRequest(
                runId,
                "session-1",
                "alice",
                "api",
                "alice",
                claim,
                "USER",
                "original",
                "effective",
                "system",
                List.of("web"),
                Map.of("providerId", "test", "model", "test-model")
        );
    }

    private static MemoryScope scope() {
        return MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        ));
    }

    private static MemoryFrame frame(String runId, String frameHash) {
        return new MemoryFrame(
                runId,
                scope(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                T0,
                frameHash
        );
    }

    private static MemoryRetrievalTrace trace(String runId, MemoryScope scope, String frameHash) {
        return new MemoryRetrievalTrace(
                runId,
                scope,
                frameHash,
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                T0
        );
    }
}
