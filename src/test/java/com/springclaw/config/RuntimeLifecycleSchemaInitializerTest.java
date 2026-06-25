package com.springclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuntimeLifecycleSchemaInitializerTest {

    @Test
    void executesSplitMigrationStatementsWhenEnabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RuntimeLifecycleSchemaInitializer initializer =
                new RuntimeLifecycleSchemaInitializer(
                        jdbcTemplate,
                        new ByteArrayResource("""
                                -- comment
                                CREATE TABLE runtime_run_state (run_id VARCHAR(64));

                                CREATE TABLE runtime_run_event (event_id VARCHAR(64));
                                """.getBytes(StandardCharsets.UTF_8)),
                        true
                );

        initializer.run(new DefaultApplicationArguments());

        verify(jdbcTemplate).execute(
                "CREATE TABLE runtime_run_state (run_id VARCHAR(64))"
        );
        verify(jdbcTemplate).execute(
                "CREATE TABLE runtime_run_event (event_id VARCHAR(64))"
        );
    }

    @Test
    void doesNothingWhenDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RuntimeLifecycleSchemaInitializer initializer =
                new RuntimeLifecycleSchemaInitializer(
                        jdbcTemplate,
                        new ByteArrayResource(
                                "CREATE TABLE runtime_run_state (run_id VARCHAR(64));"
                                        .getBytes(StandardCharsets.UTF_8)
                        ),
                        false
                );

        initializer.run(new DefaultApplicationArguments());

        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }
}
