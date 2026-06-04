package com.springclaw.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaPolicyTest {

    @Test
    void schemaShouldContainRuntimeIndexesForHotQueries() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS `agent_run`")
                .contains("CREATE TABLE IF NOT EXISTS `agent_run_step`")
                .contains("CREATE TABLE IF NOT EXISTS `tool_invocation`")
                .contains("uk_agent_run_request")
                .contains("idx_agent_run_user_time")
                .contains("idx_agent_run_step_request_sequence")
                .contains("idx_tool_invocation_request")
                .contains("idx_message_event_session_filter_id")
                .contains("idx_message_event_request_filter_id")
                .contains("idx_message_event_trace_recent")
                .contains("uk_tool_permission_role_tool")
                .contains("uk_skill_policy_scope_skill")
                .contains("idx_llm_usage_cache_create")
                .contains("idx_scheduled_task_due_dispatch")
                .contains("idx_scheduled_task_execution_task_recent");
    }

    @Test
    void migrationShouldBeIdempotentAndCoverExistingDatabases() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/sql/migrations/2026-06-03-optimize-runtime-indexes.sql"));

        assertThat(migration)
                .contains("information_schema.statistics")
                .contains("springclaw_add_index_if_missing")
                .contains("idx_message_event_session_filter_id")
                .contains("idx_message_event_request_filter_id")
                .contains("uk_tool_permission_role_tool")
                .contains("uk_skill_policy_scope_skill")
                .contains("idx_llm_usage_cache_create");
    }
}
