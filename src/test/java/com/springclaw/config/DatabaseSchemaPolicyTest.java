package com.springclaw.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaPolicyTest {

    private static final String V1 = "src/main/resources/db/migration/V1__initial_schema.sql";
    private static final String V2 = "src/main/resources/db/migration/V2__tool_invocation_proposal.sql";
    private static final String V3 = "src/main/resources/db/migration/V3__runtime_run_lifecycle.sql";

    @Test
    void v1ShouldContainCoreTablesHotQueryIndexesAndEvaluationRun() throws Exception {
        String schema = Files.readString(Path.of(V1));

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS `agent_run`")
                .contains("CREATE TABLE IF NOT EXISTS `agent_run_step`")
                .contains("CREATE TABLE IF NOT EXISTS `tool_invocation`")
                .contains("CREATE TABLE IF NOT EXISTS `runtime_evaluation_run`")
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
                .contains("idx_runtime_eval_type_time");
    }

    @Test
    void v2ShouldCreateToolInvocationProposalTable() throws Exception {
        String migration = Files.readString(Path.of(V2));

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS `tool_invocation_proposal`")
                .contains("proposal_id")
                .contains("status")
                .contains("expires_at");
    }

    @Test
    void v3ShouldCreateRuntimeRunLifecycleTables() throws Exception {
        String migration = Files.readString(Path.of(V3));

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS `runtime_run_state`")
                .contains("CREATE TABLE IF NOT EXISTS `runtime_run_event`")
                .contains("run_id")
                .contains("sequence_no");
    }
}
