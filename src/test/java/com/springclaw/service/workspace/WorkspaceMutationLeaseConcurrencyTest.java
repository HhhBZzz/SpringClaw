package com.springclaw.service.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class WorkspaceMutationLeaseConcurrencyTest {

    @TempDir
    Path workspaceRoot;

    @Autowired
    WorkspaceMutationLeaseCoordinator coordinator;

    @Autowired
    WorkspaceIdentity identity;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        jdbcTemplate.update(
                "DELETE FROM workspace_mutation_lease WHERE workspace_id = ?",
                identity.id(workspaceRoot));
    }

    @Test
    void competingTransactionCannotEnterUntilCurrentExecutionAndPublicationFinish() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirstExit = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicLong firstToken = new AtomicLong();
        AtomicLong secondToken = new AtomicLong();

        Future<String> first = executor.submit(() -> coordinator.executeExclusive(
                workspaceRoot, "proposal-first", lease -> {
                    firstToken.set(lease.fencingToken());
                    firstEntered.countDown();
                    assertThat(allowFirstExit.await(5, TimeUnit.SECONDS)).isTrue();
                    return "first";
                }));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Future<String> second = executor.submit(() -> coordinator.executeExclusive(
                workspaceRoot, "proposal-second", lease -> {
                    secondToken.set(lease.fencingToken());
                    secondEntered.countDown();
                    return "second";
                }));

        assertThat(secondEntered.await(500, TimeUnit.MILLISECONDS)).isFalse();
        allowFirstExit.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
        assertThat(secondEntered.getCount()).isZero();
        assertThat(secondToken.get()).isGreaterThan(firstToken.get());
    }
}
