CREATE TABLE IF NOT EXISTS `agent_session` (
  `id` BIGINT NOT NULL,
  `session_key` VARCHAR(128) NOT NULL,
  `channel` VARCHAR(32) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `last_user_message` TEXT NULL,
  `last_assistant_message` TEXT NULL,
  `soul_version` VARCHAR(32) NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_key` (`session_key`),
  KEY `idx_channel_user` (`channel`, `user_id`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `message_event` (
  `id` BIGINT NOT NULL,
  `session_key` VARCHAR(128) NOT NULL,
  `channel` VARCHAR(32) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `role` VARCHAR(32) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `request_id` VARCHAR(64) NULL,
  `content` TEXT NOT NULL,
  `raw_payload` TEXT NULL,
  `processing_status` VARCHAR(32) NULL,
  `error_message` VARCHAR(512) NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_session_role` (`session_key`, `role`),
  KEY `idx_session_create_time` (`session_key`, `create_time`),
  KEY `idx_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `skill_descriptor` (
  `id` BIGINT NOT NULL,
  `skill_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) NULL,
  `tool_pack` VARCHAR(64) NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `priority` INT NOT NULL DEFAULT 100,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_id` (`skill_id`),
  KEY `idx_enabled_priority` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `skill_policy` (
  `id` BIGINT NOT NULL,
  `channel` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `skill_id` VARCHAR(64) NOT NULL,
  `allow` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_scope` (`channel`, `user_id`),
  KEY `idx_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `user_account` (
  `id` BIGINT NOT NULL,
  `username` VARCHAR(64) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `role_code` VARCHAR(32) NOT NULL DEFAULT 'USER',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account_username` (`username`),
  KEY `idx_user_account_role` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `role_definition` (
  `id` BIGINT NOT NULL,
  `role_code` VARCHAR(32) NOT NULL,
  `role_name` VARCHAR(64) NOT NULL,
  `description` VARCHAR(255) NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_definition_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `tool_permission` (
  `id` BIGINT NOT NULL,
  `role_code` VARCHAR(32) NOT NULL,
  `tool_name` VARCHAR(128) NOT NULL,
  `allow` TINYINT NOT NULL DEFAULT 1,
  `priority` INT NOT NULL DEFAULT 0,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tool_permission_role` (`role_code`),
  KEY `idx_tool_permission_tool` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `llm_usage_record` (
  `id` BIGINT NOT NULL,
  `request_id` VARCHAR(64) NULL,
  `session_key` VARCHAR(128) NULL,
  `channel` VARCHAR(32) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `provider_id` VARCHAR(64) NOT NULL,
  `model` VARCHAR(128) NOT NULL,
  `source` VARCHAR(64) NOT NULL,
  `usage_known` TINYINT NOT NULL DEFAULT 0,
  `prompt_tokens` INT NULL,
  `completion_tokens` INT NULL,
  `total_tokens` INT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  `create_by` VARCHAR(64) NULL,
  `update_by` VARCHAR(64) NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_llm_usage_request` (`request_id`),
  KEY `idx_llm_usage_session` (`session_key`, `create_time`),
  KEY `idx_llm_usage_user` (`user_id`, `create_time`),
  KEY `idx_llm_usage_provider_model` (`provider_id`, `model`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
