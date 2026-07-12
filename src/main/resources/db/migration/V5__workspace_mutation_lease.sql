CREATE TABLE IF NOT EXISTS `workspace_mutation_lease` (
  `workspace_id` CHAR(64) NOT NULL,
  `holder_proposal_id` VARCHAR(64) NULL,
  `fencing_token` BIGINT NOT NULL DEFAULT 0,
  `lease_until` DATETIME(6) NULL,
  `update_time` DATETIME(6) NOT NULL,

  PRIMARY KEY (`workspace_id`),
  KEY `idx_workspace_mutation_lease_until` (`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
