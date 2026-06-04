-- SpringClaw Agent quality score columns.
-- Non-destructive migration for existing runtime console tables.

DELIMITER //

DROP PROCEDURE IF EXISTS springclaw_add_column_if_missing//
CREATE PROCEDURE springclaw_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_alter_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
        LIMIT 1
    ) THEN
        SET @springclaw_alter_sql = p_alter_sql;
        PREPARE springclaw_stmt FROM @springclaw_alter_sql;
        EXECUTE springclaw_stmt;
        DEALLOCATE PREPARE springclaw_stmt;
    END IF;
END//

DELIMITER ;

CALL springclaw_add_column_if_missing(
    'agent_run',
    'quality_score',
    'ALTER TABLE agent_run ADD COLUMN quality_score INT NULL AFTER total_tokens'
);

CALL springclaw_add_column_if_missing(
    'agent_run',
    'quality_level',
    'ALTER TABLE agent_run ADD COLUMN quality_level VARCHAR(32) NULL AFTER quality_score'
);

CALL springclaw_add_column_if_missing(
    'agent_run',
    'evaluation_json',
    'ALTER TABLE agent_run ADD COLUMN evaluation_json TEXT NULL AFTER quality_level'
);

CALL springclaw_add_column_if_missing(
    'agent_run_step',
    'quality_score',
    'ALTER TABLE agent_run_step ADD COLUMN quality_score INT NULL AFTER duration_ms'
);

CALL springclaw_add_column_if_missing(
    'agent_run_step',
    'quality_level',
    'ALTER TABLE agent_run_step ADD COLUMN quality_level VARCHAR(32) NULL AFTER quality_score'
);

DROP PROCEDURE IF EXISTS springclaw_add_column_if_missing;
