-- src/main/resources/sql/migrations/2026-06-17-tool-invocation-proposal.sql
CREATE TABLE IF NOT EXISTS `tool_invocation_proposal` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `proposal_id` VARCHAR(64) NOT NULL,
  `request_id` VARCHAR(64) NOT NULL,
  `run_id` VARCHAR(64) NULL,
  `session_key` VARCHAR(128) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `role_code` VARCHAR(32) NULL,

  `tool_name` VARCHAR(128) NOT NULL,
  `toolset_id` VARCHAR(64) NOT NULL,
  `arguments_canonical_json` TEXT NOT NULL,
  `arguments_hash` VARCHAR(64) NOT NULL,
  `risk_level` VARCHAR(32) NOT NULL,
  `target_paths` TEXT NOT NULL,
  `preview_summary` VARCHAR(1024) NULL,

  `workspace_dirty_at_create` TINYINT NOT NULL DEFAULT 0,
  `dirty_files_at_create` TEXT NULL,

  `status` VARCHAR(32) NOT NULL,
  `version` INT NOT NULL DEFAULT 0,

  `executed_at` DATETIME NULL,
  `execution_result` TEXT NULL,
  `execution_error` VARCHAR(1024) NULL,

  `git_head_sha_at_create` VARCHAR(40) NULL,
  `git_baseline_sha` VARCHAR(40) NULL,
  `git_commit_sha` VARCHAR(40) NULL,
  `git_changed_files` TEXT NULL,

  `reviewed_at` DATETIME NULL,
  `review_reason` VARCHAR(512) NULL,

  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `expires_at` DATETIME NOT NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_invocation_proposal_id` (`proposal_id`),
  KEY `idx_tool_invocation_request` (`request_id`),
  KEY `idx_tool_invocation_session_status` (`session_key`, `status`, `deleted`),
  KEY `idx_tool_invocation_status_expires` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
