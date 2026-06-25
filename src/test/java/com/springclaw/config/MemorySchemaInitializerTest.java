package com.springclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3A1 Task 3：MemorySchemaInitializer 在启动时幂等执行 memory-core migration。
 *
 * 不依赖真实数据库——用 mock JdbcTemplate 验证：
 *   - enabled=false 时不执行任何语句；
 *   - enabled=true 时按分号拆分并逐条执行 migration SQL；
 *   - enabled=true 时 migration 资源缺失或 SQL 执行失败均 fail loud。
 */
class MemorySchemaInitializerTest {

    @Test
    void disabledInitializerExecutesNothing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MemorySchemaInitializer initializer = new MemorySchemaInitializer(
                jdbcTemplate, new ByteArrayResource("-- nothing".getBytes()), false);

        initializer.run(new org.springframework.boot.DefaultApplicationArguments());

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void enabledInitializerSplitsAndExecutesMigrationStatements() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String sql = """
                CREATE TABLE IF NOT EXISTS memory_record (id BIGINT NOT NULL);
                CREATE TABLE IF NOT EXISTS memory_index_outbox (id BIGINT NOT NULL);
                """;
        MemorySchemaInitializer initializer = new MemorySchemaInitializer(
                jdbcTemplate, new ByteArrayResource(sql.getBytes()), true);

        initializer.run(new org.springframework.boot.DefaultApplicationArguments());

        verify(jdbcTemplate, times(2)).execute(anyString());
    }

    @Test
    void missingResourceThrows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        org.springframework.core.io.Resource missing =
                mock(org.springframework.core.io.Resource.class);
        when(missing.exists()).thenReturn(false);
        MemorySchemaInitializer initializer = new MemorySchemaInitializer(
                jdbcTemplate, missing, true);

        assertThatThrownBy(() ->
                initializer.run(new org.springframework.boot.DefaultApplicationArguments())
        ).isInstanceOf(IllegalStateException.class);

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void migrationExecutionFailureThrows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("boom"))
                .when(jdbcTemplate).execute(anyString());
        MemorySchemaInitializer initializer = new MemorySchemaInitializer(
                jdbcTemplate,
                new ByteArrayResource("CREATE TABLE broken (id BIGINT);".getBytes()),
                true
        );

        assertThatThrownBy(() ->
                initializer.run(new org.springframework.boot.DefaultApplicationArguments())
        ).isInstanceOf(IllegalStateException.class)
         .hasCauseInstanceOf(RuntimeException.class);
    }
}
