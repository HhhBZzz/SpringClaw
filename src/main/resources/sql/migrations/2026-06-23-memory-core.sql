-- Phase 3A1 memory core schema migration (2026-06-23)
-- Idempotent on an existing database via information-schema guards.

-- ---------------------------------------------------------------------------
-- memory_record: immutable versioned memory authority
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `memory_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `logical_memory_id` VARCHAR(64) NOT NULL,
  `memory_version_id` VARCHAR(64) NOT NULL,
  `memory_type` VARCHAR(32) NOT NULL,
  `scope_type` VARCHAR(32) NOT NULL,
  `scope_id` VARCHAR(256) NOT NULL,
  `owner_user_id` VARCHAR(64) NULL,
  `content` TEXT NOT NULL,
  `content_hash` VARCHAR(64) NOT NULL,
  `summary` VARCHAR(2048) NULL,
  `source_run_id` VARCHAR(64) NULL,
  `source_event_ids_json` TEXT NULL,
  `evidence_refs_json` TEXT NULL,
  `tags_json` TEXT NULL,
  `importance` DECIMAL(5,4) NOT NULL,
  `confidence` DECIMAL(5,4) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `valid_from` DATETIME(3) NOT NULL,
  `valid_until` DATETIME(3) NULL,
  `supersedes_record_id` BIGINT NULL,
  `version` INT NOT NULL,
  `active_slot` TINYINT NULL,
  `source_kind` VARCHAR(32) NULL,
  `source_identity` VARCHAR(192) NULL,
  `extraction_policy_version` VARCHAR(64) NULL,
  `index_revision` BIGINT NOT NULL,
  `create_time` DATETIME(3) NOT NULL,
  `update_time` DATETIME(3) NOT NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_version_id` (`memory_version_id`),
  UNIQUE KEY `uk_memory_logical_version` (`logical_memory_id`, `version`),
  UNIQUE KEY `uk_memory_single_active` (`logical_memory_id`, `active_slot`),
  UNIQUE KEY `uk_memory_source_policy`
    (`source_kind`, `source_identity`, `extraction_policy_version`, `memory_type`),
  KEY `idx_memory_scope_active`
    (`scope_type`, `scope_id`, `status`, `deleted`, `update_time`),
  KEY `idx_memory_index_revision` (`index_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- memory_index_outbox: fenced vector indexing work queue
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `memory_index_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(64) NOT NULL,
  `logical_memory_id` VARCHAR(64) NOT NULL,
  `memory_version_id` VARCHAR(64) NOT NULL,
  `memory_version` INT NOT NULL,
  `index_revision` BIGINT NOT NULL,
  `operation` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `attempts` INT NOT NULL DEFAULT 0,
  `available_at` DATETIME(3) NOT NULL,
  `claimed_at` DATETIME(3) NULL,
  `claim_owner` VARCHAR(128) NULL,
  `claim_token` VARCHAR(64) NULL,
  `lease_until` DATETIME(3) NULL,
  `last_error` VARCHAR(2048) NULL,
  `create_time` DATETIME(3) NOT NULL,
  `update_time` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_outbox_event` (`event_id`),
  UNIQUE KEY `uk_memory_outbox_revision`
    (`logical_memory_id`, `index_revision`, `operation`),
  KEY `idx_memory_outbox_claim`
    (`status`, `available_at`, `lease_until`, `id`),
  KEY `idx_memory_outbox_logical_revision`
    (`logical_memory_id`, `index_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- message_event.event_key: deterministic chat-event identity
-- Idempotent: add column + backfill + unique key only when absent.
-- ---------------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'message_event'
    AND COLUMN_NAME = 'event_key');

SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `message_event` ADD COLUMN `event_key` VARCHAR(192) NULL AFTER `request_id`',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- backfill existing rows with a stable legacy identity before enforcing uniqueness
SET @backfilled = (SELECT COUNT(*) FROM `message_event` WHERE `event_key` IS NULL);
SET @sql = IF(@backfilled > 0,
  'UPDATE `message_event` SET `event_key` = CONCAT(''legacy:'', `id`) WHERE `event_key` IS NULL',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @key_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'message_event'
    AND INDEX_NAME = 'uk_message_event_event_key');

SET @sql = IF(@key_exists = 0,
  'ALTER TABLE `message_event` ADD UNIQUE KEY `uk_message_event_event_key` (`event_key`)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
