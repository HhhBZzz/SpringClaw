package com.springclaw.service.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.memory.store.MySqlMemoryIndexOutboxStore;
import com.springclaw.service.memory.store.MySqlMemoryRecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class MemoryManagementServiceIT {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final MemoryScope SCOPE = MemoryScope.from(
            SessionAccessClaim.personal(
                    SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    "api",
                    "management-it",
                    "alice"
            )
    );

    @Autowired
    private MemoryManagementService service;

    @MockitoSpyBean
    private MySqlMemoryRecordStore recordStore;

    @Autowired
    private MySqlMemoryIndexOutboxStore outboxStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanMemoryTables() {
        jdbcTemplate.update("DELETE FROM memory_index_outbox");
        jdbcTemplate.update("DELETE FROM memory_record");
    }

    @AfterEach
    void removeCommittedTestData() {
        cleanMemoryTables();
    }

    @Test
    void mysqlLifecycleCommitsRecordAndOutboxTogether() {
        MemoryRecordVersion created = service.create(command(
                "logical-it-success",
                MemoryStatus.ACTIVE
        ));

        assertThat(recordStore.findActive(created.logicalMemoryId()))
                .contains(created);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_index_outbox",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void mysqlLifecycleRollsBackRecordWhenOutboxInsertFails() {
        String logicalMemoryId = "logical-it-rollback";
        MemoryVersionFactory deterministic =
                new MemoryVersionFactory(Clock.systemUTC());
        String versionId = deterministic.memoryVersionId(logicalMemoryId, 1);
        String eventId = deterministic.outboxEventId(
                versionId,
                1L,
                MemoryIndexOperation.UPSERT
        );
        outboxStore.insert(new MemoryIndexOutboxEntry(
                eventId,
                "collision-logical",
                "collision-version",
                1,
                1L,
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
        ));

        assertThatThrownBy(() -> service.create(command(
                logicalMemoryId,
                MemoryStatus.ACTIVE
        ))).isInstanceOf(RuntimeException.class);

        assertThat(recordStore.findByVersionId(versionId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_record",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_index_outbox",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void mysqlAutomaticSourceLookupReturnsPersistedVersion() {
        MemoryWriteCommand command = autoCommand();

        MemoryRecordVersion first = service.create(command);
        MemoryRecordVersion second = service.create(command);

        assertThat(second.recordId()).isEqualTo(first.recordId());
        assertThat(second.memoryVersionId()).isEqualTo(first.memoryVersionId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_record",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_index_outbox",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void concurrentAutomaticCreatesReturnTheSameCommittedVersion() {
        MemoryWriteCommand command = autoCommand();
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier bothReadMissing = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result instanceof java.util.Optional<?> optional
                    && optional.isEmpty()) {
                bothReadMissing.await(5, TimeUnit.SECONDS);
            }
            return result;
        }).when(recordStore).findByAutomaticSource(
                anyString(),
                anyString(),
                anyString(),
                any(MemoryType.class)
        );

        CompletableFuture<MemoryRecordVersion> first =
                CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return service.create(command);
                });
        CompletableFuture<MemoryRecordVersion> second =
                CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return service.create(command);
                });
        start.countDown();

        MemoryRecordVersion firstResult = first.join();
        MemoryRecordVersion secondResult = second.join();

        assertThat(secondResult.memoryVersionId())
                .isEqualTo(firstResult.memoryVersionId());
        assertThat(secondResult.recordId()).isEqualTo(firstResult.recordId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_record",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_index_outbox",
                Integer.class
        )).isEqualTo(1);
    }

    private static MemoryWriteCommand command(
            String logicalMemoryId,
            MemoryStatus status
    ) {
        return new MemoryWriteCommand(
                logicalMemoryId,
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

    private static MemoryWriteCommand autoCommand() {
        return new MemoryWriteCommand(
                "logical-it-auto",
                MemoryType.SEMANTIC,
                SCOPE,
                "automatic preference",
                "summary",
                "run-it",
                List.of("event-it"),
                List.of("event-it"),
                List.of("automatic"),
                0.8,
                0.9,
                MemoryStatus.ACTIVE,
                T0,
                null,
                "EXTRACTION",
                "run-it:preference",
                "v1"
        );
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
