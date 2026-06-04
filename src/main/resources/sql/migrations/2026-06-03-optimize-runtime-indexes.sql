-- SpringClaw runtime schema optimization.
-- Non-destructive migration: only adds missing unique keys and secondary indexes.

DELIMITER //

DROP PROCEDURE IF EXISTS springclaw_add_index_if_missing//
CREATE PROCEDURE springclaw_add_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_alter_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
        LIMIT 1
    ) THEN
        SET @springclaw_alter_sql = p_alter_sql;
        PREPARE springclaw_stmt FROM @springclaw_alter_sql;
        EXECUTE springclaw_stmt;
        DEALLOCATE PREPARE springclaw_stmt;
    END IF;
END//

DELIMITER ;

CALL springclaw_add_index_if_missing(
    'agent_session',
    'idx_agent_session_channel_user_update',
    'ALTER TABLE agent_session ADD INDEX idx_agent_session_channel_user_update (channel, user_id, deleted, update_time)'
);

CALL springclaw_add_index_if_missing(
    'message_event',
    'idx_message_event_session_id',
    'ALTER TABLE message_event ADD INDEX idx_message_event_session_id (session_key, deleted, id)'
);

CALL springclaw_add_index_if_missing(
    'message_event',
    'idx_message_event_session_filter_id',
    'ALTER TABLE message_event ADD INDEX idx_message_event_session_filter_id (session_key, user_id, event_type, role, deleted, id)'
);

CALL springclaw_add_index_if_missing(
    'message_event',
    'idx_message_event_request_filter_id',
    'ALTER TABLE message_event ADD INDEX idx_message_event_request_filter_id (request_id, user_id, event_type, role, deleted, id)'
);

CALL springclaw_add_index_if_missing(
    'message_event',
    'idx_message_event_trace_recent',
    'ALTER TABLE message_event ADD INDEX idx_message_event_trace_recent (event_type, role, deleted, id)'
);

CALL springclaw_add_index_if_missing(
    'message_event',
    'idx_message_event_user_recent',
    'ALTER TABLE message_event ADD INDEX idx_message_event_user_recent (user_id, deleted, id)'
);

CALL springclaw_add_index_if_missing(
    'skill_policy',
    'uk_skill_policy_scope_skill',
    'ALTER TABLE skill_policy ADD UNIQUE KEY uk_skill_policy_scope_skill (channel, user_id, skill_id)'
);

CALL springclaw_add_index_if_missing(
    'tool_permission',
    'uk_tool_permission_role_tool',
    'ALTER TABLE tool_permission ADD UNIQUE KEY uk_tool_permission_role_tool (role_code, tool_name)'
);

CALL springclaw_add_index_if_missing(
    'tool_permission',
    'idx_tool_permission_role_enabled_priority',
    'ALTER TABLE tool_permission ADD INDEX idx_tool_permission_role_enabled_priority (role_code, enabled, priority)'
);

CALL springclaw_add_index_if_missing(
    'llm_usage_record',
    'idx_llm_usage_create_time',
    'ALTER TABLE llm_usage_record ADD INDEX idx_llm_usage_create_time (create_time)'
);

CALL springclaw_add_index_if_missing(
    'llm_usage_record',
    'idx_llm_usage_source_create',
    'ALTER TABLE llm_usage_record ADD INDEX idx_llm_usage_source_create (source, create_time)'
);

CALL springclaw_add_index_if_missing(
    'llm_usage_record',
    'idx_llm_usage_cache_create',
    'ALTER TABLE llm_usage_record ADD INDEX idx_llm_usage_cache_create (provider_id, model, source, create_time)'
);

CALL springclaw_add_index_if_missing(
    'scheduled_task',
    'idx_scheduled_task_owner_state_update',
    'ALTER TABLE scheduled_task ADD INDEX idx_scheduled_task_owner_state_update (owner_user_id, enabled, deleted, update_time)'
);

CALL springclaw_add_index_if_missing(
    'scheduled_task',
    'idx_scheduled_task_due_dispatch',
    'ALTER TABLE scheduled_task ADD INDEX idx_scheduled_task_due_dispatch (enabled, deleted, next_run_at)'
);

CALL springclaw_add_index_if_missing(
    'scheduled_task',
    'idx_scheduled_task_status_update',
    'ALTER TABLE scheduled_task ADD INDEX idx_scheduled_task_status_update (last_status, deleted, update_time)'
);

CALL springclaw_add_index_if_missing(
    'scheduled_task_execution',
    'idx_scheduled_task_execution_task_recent',
    'ALTER TABLE scheduled_task_execution ADD INDEX idx_scheduled_task_execution_task_recent (task_id, deleted, started_at)'
);

CALL springclaw_add_index_if_missing(
    'scheduled_task_execution',
    'idx_scheduled_task_execution_status_time',
    'ALTER TABLE scheduled_task_execution ADD INDEX idx_scheduled_task_execution_status_time (status, deleted, started_at)'
);

DROP PROCEDURE IF EXISTS springclaw_add_index_if_missing;
