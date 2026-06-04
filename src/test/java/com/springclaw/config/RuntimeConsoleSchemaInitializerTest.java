package com.springclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConsoleSchemaInitializerTest {

    @Test
    void shouldSplitRuntimeConsoleMigrationIntoCreateTableStatements() {
        String sql = """
                -- comment
                CREATE TABLE IF NOT EXISTS `agent_run` (`id` BIGINT);
                CREATE TABLE IF NOT EXISTS `agent_run_step` (`id` BIGINT);
                CREATE TABLE IF NOT EXISTS `tool_invocation` (`id` BIGINT);
                """;

        List<String> statements = RuntimeConsoleSchemaInitializer.splitSqlStatements(sql);

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("agent_run");
        assertThat(statements.get(1)).contains("agent_run_step");
        assertThat(statements.get(2)).contains("tool_invocation");
    }

    @Test
    void shouldAddAgentQualityColumnsWhenRuntimeTablesAlreadyExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(), any())).thenReturn(0);
        RuntimeConsoleSchemaInitializer initializer = new RuntimeConsoleSchemaInitializer(
                jdbcTemplate,
                new ByteArrayResource(new byte[0]),
                true
        );

        initializer.run(null);

        verify(jdbcTemplate, times(5)).execute(startsWith("ALTER TABLE"));
    }
}
