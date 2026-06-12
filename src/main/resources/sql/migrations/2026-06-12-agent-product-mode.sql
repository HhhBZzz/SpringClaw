-- Add product-facing run mode for existing runtime console databases.
SET @springclaw_product_mode_missing := (
  SELECT COUNT(1) = 0
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'agent_run'
    AND column_name = 'product_mode'
);

SET @springclaw_product_mode_sql := IF(
  @springclaw_product_mode_missing,
  'ALTER TABLE agent_run ADD COLUMN product_mode VARCHAR(32) NULL AFTER user_id',
  'SELECT 1'
);

PREPARE springclaw_product_mode_stmt FROM @springclaw_product_mode_sql;
EXECUTE springclaw_product_mode_stmt;
DEALLOCATE PREPARE springclaw_product_mode_stmt;
