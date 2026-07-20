-- Phase 3E runtime lifecycle authority.
-- Stores canonical RunState and ordered RunEvent facts durably.

CREATE TABLE IF NOT EXISTS `runtime_run_state` (
  `run_id` VARCHAR(64) NOT NULL,
  `request_id` VARCHAR(64) NOT NULL,
  `session_key` VARCHAR(128) NOT NULL,
  `channel` VARCHAR(32) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `revision` BIGINT NOT NULL,
  `accepted_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `deadline_at` DATETIME(3) NOT NULL,
  `state_json` LONGTEXT NOT NULL,
  `create_time` DATETIME(3) NOT NULL,
  `update_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`run_id`),
  KEY `idx_runtime_run_state_request` (`request_id`),
  KEY `idx_runtime_run_state_session` (`session_key`, `channel`, `user_id`, `updated_at`),
  KEY `idx_runtime_run_state_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `runtime_run_event` (
  `event_id` VARCHAR(64) NOT NULL,
  `run_id` VARCHAR(64) NOT NULL,
  `sequence_no` BIGINT NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `stage` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `correlation_id` VARCHAR(64) NOT NULL,
  `event_json` LONGTEXT NOT NULL,
  `create_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_runtime_run_event_sequence` (`run_id`, `sequence_no`),
  KEY `idx_runtime_run_event_run_type` (`run_id`, `event_type`, `sequence_no`),
  KEY `idx_runtime_run_event_time` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
