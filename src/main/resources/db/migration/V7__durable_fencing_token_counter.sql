CREATE TABLE `workspace_fencing_token_counter` (
  `counter_id` TINYINT NOT NULL,
  `last_token` BIGINT NOT NULL,
  `update_time` DATETIME(6) NOT NULL,

  PRIMARY KEY (`counter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `workspace_fencing_token_counter` (`counter_id`, `last_token`, `update_time`)
SELECT 1, COALESCE(MAX(`fencing_token`), 0), CURRENT_TIMESTAMP(6)
FROM `workspace_mutation_lease`;
