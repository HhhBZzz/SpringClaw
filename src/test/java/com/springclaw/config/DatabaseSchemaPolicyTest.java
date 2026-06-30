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
                .contains("quality_score")
                .contains("quality_level")
                .contains("evaluation_json")
                .contains("product_mode")
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
                .contains("idx_scheduled_task_execution_task_recent")
                .contains("CREATE TABLE IF NOT EXISTS `runtime_evaluation_run`")
                .contains("idx_runtime_eval_type_time");
    }

    @Test
    void migrationShouldAddAgentQualityColumnsForExistingDatabases() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/sql/migrations/2026-06-05-agent-quality-score.sql"));

        assertThat(migration)
                .contains("agent_run")
                .contains("quality_score")
                .contains("quality_level")
                .contains("evaluation_json")
                .contains("information_schema.columns");
    }

    @Test
    void migrationShouldAddAgentProductModeForExistingDatabases() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/sql/migrations/2026-06-12-agent-product-mode.sql"));

        assertThat(migration)
                .contains("agent_run")
                .contains("product_mode")
                .contains("information_schema.columns");
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

    @Test
    void runtimeConsoleMigrationShouldCreateEvaluationHistoryTable() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/sql/migrations/2026-06-04-runtime-console-run-tables.sql"));

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS `runtime_evaluation_run`")
                .contains("evaluation_type")
                .contains("schema_version")
                .contains("result_json")
                .contains("idx_runtime_eval_type_time");
    }
}
