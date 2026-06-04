package com.springclaw.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
