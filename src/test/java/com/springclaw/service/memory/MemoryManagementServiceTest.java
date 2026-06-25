package com.springclaw.service.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MemoryManagementServiceTest {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final MemoryScope SCOPE = MemoryScope.from(
            SessionAccessClaim.personal(
                    SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    "api",
                    "session-1",
                    "alice"
            )
    );

    private InMemoryMemoryRecordStore recordStore;
    private InMemoryMemoryIndexOutboxStore outboxStore;
    private MemoryVersionFactory factory;
    private MemoryManagementService service;

    @BeforeEach
    void setUp() {
        recordStore = new InMemoryMemoryRecordStore();
        outboxStore = new InMemoryMemoryIndexOutboxStore(CLOCK);
        factory = new MemoryVersionFactory(CLOCK);
        service = new MemoryManagementService(recordStore, outboxStore, factory);
    }

    @Test
    void commandNormalizesCopiesAndRejectsInvalidLifecycleInput() {
        List<String> sourceEvents = new ArrayList<>(List.of(" event-1 "));
        MemoryWriteCommand command = new MemoryWriteCommand(
                " logical-1 ",
                MemoryType.SEMANTIC,
                SCOPE,
                "  durable   preference  ",
                " summary ",
                " run-1 ",
                sourceEvents,
                List.of(" evidence-1 "),
                List.of(" preference "),
                0.8,
                0.9,
                MemoryStatus.CANDIDATE,
                T0,
                T0.plusSeconds(60),
                " EXTRACTION ",
                " run-1:preference ",
                " v1 "
        );
        sourceEvents.add("event-2");

        assertThat(command.logicalMemoryId()).isEqualTo("logical-1");
        assertThat(command.content()).isEqualTo("durable preference");
        assertThat(command.sourceEventIds()).containsExactly("event-1");
        assertThatThrownBy(() -> command.tags().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command(MemoryStatus.REJECTED))
                .hasMessageContaining("requestedStatus");
        assertThatThrownBy(() -> new MemoryWriteCommand(
                "logical-1", MemoryType.SEMANTIC, SCOPE, "content", null,
                "run-1", List.of(), List.of(), List.of(), 1.1, 0.5,
                MemoryStatus.CANDIDATE, T0, null,
                null, null, null
        )).hasMessageContaining("importance");
        assertThatThrownBy(() -> new MemoryWriteCommand(
                "logical-1", MemoryType.SEMANTIC, SCOPE, "content", null,
                "run-1", List.of(), List.of(), List.of(), 0.5, 0.5,
                MemoryStatus.CANDIDATE, T0.plusSeconds(1), T0,
                "EXTRACTION", "source-1", "v1"
        )).hasMessageContaining("validUntil");
    }

    @Test
    void factoryUsesNormalizedHashStableIdsAndInjectedClock() {
        MemoryRecordVersion first = factory.create(
                command(MemoryStatus.CANDIDATE),
                1,
                1L,
                null,
                MemoryStatus.CANDIDATE,
                null
        );
        MemoryRecordVersion same = factory.create(
                command(MemoryStatus.CANDIDATE),
                1,
                1L,
                null,
                MemoryStatus.CANDIDATE,
                null
        );

        assertThat(first.memoryVersionId())
                .isEqualTo(same.memoryVersionId())
                .hasSize(64);
        assertThat(first.contentHash()).hasSize(64);
        assertThat(first.createdAt()).isEqualTo(T0);
        assertThat(first.updatedAt()).isEqualTo(T0);
    }

    @Test
    void createActiveWritesVersionAndUpsertOutboxAtomically() {
        MemoryRecordVersion created = service.create(command(MemoryStatus.ACTIVE));

        assertThat(recordStore.findActive(created.logicalMemoryId()))
                .contains(created);
        assertThat(outboxStore.findAll())
                .singleElement()
                .extracting(MemoryIndexOutboxEntry::operation)
                .isEqualTo(MemoryIndexOperation.UPSERT);
    }

    @Test
    void createCandidateDoesNotWriteUpsert() {
        MemoryRecordVersion created =
                service.create(command(MemoryStatus.CANDIDATE));

        assertThat(created.status()).isEqualTo(MemoryStatus.CANDIDATE);
        assertThat(recordStore.findActive(created.logicalMemoryId())).isEmpty();
        assertThat(outboxStore.findAll()).isEmpty();
    }

    @Test
    void repeatedAutomaticSourceReturnsExistingVersionButManualWritesDoNot() {
        MemoryRecordVersion first = service.create(autoCommand("run-1"));
        MemoryRecordVersion second = service.create(autoCommand("run-1"));

        assertThat(second.memoryVersionId()).isEqualTo(first.memoryVersionId());
        assertThat(outboxStore.findAll()).hasSize(1);

        service.create(manualCommand("manual-1"));
        assertThatThrownBy(() -> service.create(manualCommand("manual-1")))
                .hasMessageContaining("memoryVersionId");
    }

    @Test
    void concurrentAutomaticSourceAcrossLogicalIdsReturnsOneVersion()
            throws Exception {
        Object capacityLock = privateField(
                recordStore,
                "capacityLock"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<MemoryRecordVersion> first;
        Future<MemoryRecordVersion> second;
        synchronized (capacityLock) {
            first = executor.submit(() -> service.create(
                    autoCommand("logical-auto-a", "shared-source")
            ));
            second = executor.submit(() -> service.create(
                    autoCommand("logical-auto-b", "shared-source")
            ));
            Thread.sleep(100);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
        }
        try {
            MemoryRecordVersion firstResult = first.get(5, TimeUnit.SECONDS);
            MemoryRecordVersion secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(secondResult.memoryVersionId())
                    .isEqualTo(firstResult.memoryVersionId());
            assertThat(secondResult.recordId()).isEqualTo(firstResult.recordId());
            assertThat(outboxStore.findAll()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transitionsUseCasAndWriteOnlyActiveBoundaryEvents() {
        MemoryRecordVersion candidate =
                service.create(command(MemoryStatus.CANDIDATE));

        MemoryRecordVersion active = service.transition(
                candidate.memoryVersionId(),
                MemoryStatus.CANDIDATE,
                MemoryStatus.ACTIVE,
                T0.plusSeconds(1)
        );
        MemoryRecordVersion expired = service.transition(
                active.memoryVersionId(),
                MemoryStatus.ACTIVE,
                MemoryStatus.EXPIRED,
                T0.plusSeconds(2)
        );

        assertThat(expired.status()).isEqualTo(MemoryStatus.EXPIRED);
        assertThat(expired.indexRevision()).isEqualTo(3L);
        assertThat(outboxStore.findAll())
                .extracting(
                        MemoryIndexOutboxEntry::operation,
                        MemoryIndexOutboxEntry::indexRevision
                )
                .containsExactly(
                        tuple(MemoryIndexOperation.UPSERT, 2L),
                        tuple(MemoryIndexOperation.DELETE, 3L)
                );
        assertThatThrownBy(() -> service.transition(
                expired.memoryVersionId(),
                MemoryStatus.ACTIVE,
                MemoryStatus.REJECTED,
                T0.plusSeconds(3)
        )).hasMessageContaining("compare-and-set");
        assertThatThrownBy(() -> service.transition(
                expired.memoryVersionId(),
                MemoryStatus.EXPIRED,
                MemoryStatus.ACTIVE,
                T0.plusSeconds(3)
        )).hasMessageContaining("transition");
    }

    @Test
    void supersedeWritesDeleteBeforeNewUpsertRevision() {
        MemoryRecordVersion old = service.create(command(MemoryStatus.ACTIVE));
        MemoryRecordVersion next = service.supersede(
                old.memoryVersionId(),
                replacementCommand()
        );

        assertThat(next.logicalMemoryId()).isEqualTo(old.logicalMemoryId());
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.supersedesRecordId()).isEqualTo(old.recordId());
        assertThat(next.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(recordStore.findByVersionId(old.memoryVersionId()))
                .get()
                .extracting(
                        MemoryRecordVersion::status,
                        MemoryRecordVersion::activeSlot
                )
                .containsExactly(MemoryStatus.SUPERSEDED, null);
        assertThat(recordStore.findActive(old.logicalMemoryId())).contains(next);
        assertThat(outboxStore.findAll())
                .extracting(
                        MemoryIndexOutboxEntry::operation,
                        MemoryIndexOutboxEntry::indexRevision
                )
                .containsExactly(
                        tuple(MemoryIndexOperation.UPSERT, 1L),
                        tuple(MemoryIndexOperation.DELETE, 2L),
                        tuple(MemoryIndexOperation.UPSERT, 3L)
                );
    }

    @Test
    void inMemoryBoundaryRollsBackRecordWhenOutboxWriteFails() {
        for (int index = 0; index < 5_000; index++) {
            outboxStore.insert(pending("seed-" + index, "seed-" + index));
        }

        assertThatThrownBy(() -> service.create(command(MemoryStatus.ACTIVE)))
                .hasMessageContaining("capacity");
        assertThat(recordStore.findByVersionId(
                factory.memoryVersionId("logical-1", 1)
        )).isEmpty();
        assertThat(outboxStore.findAll()).hasSize(5_000);
    }

    @Test
    void inMemoryBoundaryRollsBackSupersedeWhenOutboxHasNoCapacity() {
        MemoryRecordVersion old = service.create(command(MemoryStatus.ACTIVE));
        for (int index = 1; index < 5_000; index++) {
            outboxStore.insert(pending("seed-" + index, "seed-" + index));
        }

        assertThatThrownBy(() -> service.supersede(
                old.memoryVersionId(),
                replacementCommand()
        )).hasMessageContaining("capacity");
        assertThat(recordStore.findActive(old.logicalMemoryId())).contains(old);
        assertThat(recordStore.findByVersionId(
                factory.memoryVersionId(old.logicalMemoryId(), 2)
        )).isEmpty();
        assertThat(outboxStore.findAll()).hasSize(5_000);
    }

    @Test
    void inMemoryBoundaryHidesHalfStateAndPreservesConcurrentWorkerUpdate()
            throws Exception {
        String logicalMemoryId = "logical-isolation";
        MemoryWriteCommand command = manualCommand(logicalMemoryId);
        MemoryRecordVersion version = factory.create(
                command,
                1,
                1L,
                null,
                MemoryStatus.ACTIVE,
                1
        );
        MemoryIndexOutboxEntry newEntry = factory.outbox(
                version,
                1L,
                MemoryIndexOperation.UPSERT,
                T0
        );
        outboxStore.insert(pending("worker-seed", logicalMemoryId, 10L));
        InMemoryMemoryTransactionBoundary boundary =
                new InMemoryMemoryTransactionBoundary(recordStore, outboxStore);
        CountDownLatch recordInserted = new CountDownLatch(1);
        CountDownLatch continueTransaction = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);

        Future<?> transaction = executor.submit(() -> boundary.execute(
                logicalMemoryId,
                () -> {
                    recordStore.insert(version);
                    recordInserted.countDown();
                    await(continueTransaction);
                    outboxStore.insert(newEntry);
                    throw new IllegalStateException("rollback");
                }
        ));
        assertThat(recordInserted.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Optional<MemoryRecordVersion>> recordRead = executor.submit(
                () -> recordStore.findByVersionId(version.memoryVersionId())
        );
        Future<List<MemoryIndexOutboxEntry>> outboxRead = executor.submit(
                outboxStore::findAll
        );
        Future<Optional<MemoryIndexOutboxEntry>> workerClaim = executor.submit(
                () -> outboxStore.claimNext(
                        "worker",
                        T0,
                        T0.plusSeconds(30)
                )
        );

        try {
            Thread.sleep(100);
            assertThat(recordRead.isDone()).isFalse();
            assertThat(outboxRead.isDone()).isFalse();
            assertThat(workerClaim.isDone()).isFalse();
        } finally {
            continueTransaction.countDown();
        }

        assertThatThrownBy(() -> transaction.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("rollback");
        assertThat(recordRead.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(outboxRead.get(5, TimeUnit.SECONDS))
                .extracting(MemoryIndexOutboxEntry::eventId)
                .containsExactly("worker-seed");
        MemoryIndexOutboxEntry claimed = workerClaim
                .get(5, TimeUnit.SECONDS)
                .orElseThrow();
        assertThat(claimed.eventId()).isEqualTo("worker-seed");
        assertThat(outboxStore.findAll())
                .singleElement()
                .extracting(MemoryIndexOutboxEntry::status)
                .isEqualTo(MemoryIndexOutboxEntry.Status.CLAIMED);
        executor.shutdownNow();
    }

    private static MemoryWriteCommand command(MemoryStatus status) {
        return new MemoryWriteCommand(
                "logical-1",
                MemoryType.SEMANTIC,
                SCOPE,
                "durable preference",
                "summary",
                null,
                List.of(),
                List.of("evidence-1"),
                List.of("preference"),
                0.8,
                0.9,
                status,
                T0,
                null,
                null,
                null,
                null
        );
    }

    private static MemoryWriteCommand autoCommand(String sourceIdentity) {
        return autoCommand("logical-auto", sourceIdentity);
    }

    private static MemoryWriteCommand autoCommand(
            String logicalMemoryId,
            String sourceIdentity
    ) {
        return new MemoryWriteCommand(
                logicalMemoryId,
                MemoryType.SEMANTIC,
                SCOPE,
                "automatic preference",
                "summary",
                "run-1",
                List.of("event-1"),
                List.of("event-1"),
                List.of("automatic"),
                0.8,
                0.9,
                MemoryStatus.ACTIVE,
                T0,
                null,
                "EXTRACTION",
                sourceIdentity,
                "v1"
        );
    }

    private static MemoryWriteCommand manualCommand(String logicalMemoryId) {
        return new MemoryWriteCommand(
                logicalMemoryId,
                MemoryType.SEMANTIC,
                SCOPE,
                "manual preference",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                0.5,
                0.5,
                MemoryStatus.CANDIDATE,
                T0,
                null,
                null,
                null,
                null
        );
    }

    private static MemoryWriteCommand replacementCommand() {
        return new MemoryWriteCommand(
                "logical-1",
                MemoryType.SEMANTIC,
                SCOPE,
                "updated durable preference",
                "updated summary",
                "run-2",
                List.of("event-2"),
                List.of("event-2"),
                List.of("preference"),
                0.9,
                0.95,
                MemoryStatus.ACTIVE,
                T0.plusSeconds(10),
                null,
                "EXTRACTION",
                "run-2",
                "v1"
        );
    }

    private static MemoryIndexOutboxEntry pending(
            String eventId,
            String logicalMemoryId
    ) {
        return pending(eventId, logicalMemoryId, 1L);
    }

    private static MemoryIndexOutboxEntry pending(
            String eventId,
            String logicalMemoryId,
            long indexRevision
    ) {
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                "version-" + eventId,
                1,
                indexRevision,
                MemoryIndexOperation.UPSERT,
                MemoryIndexOutboxEntry.Status.PENDING,
                0,
                T0,
                null,
                null,
                null,
                null,
                null,
                T0,
                T0
        );
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ex);
        }
    }
}
