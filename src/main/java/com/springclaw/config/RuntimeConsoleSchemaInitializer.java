package com.springclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class RuntimeConsoleSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConsoleSchemaInitializer.class);
    private static final String MIGRATION_PATH = "sql/migrations/2026-06-04-runtime-console-run-tables.sql";

    private final JdbcTemplate jdbcTemplate;
    private final Resource migrationResource;
    private final boolean enabled;

    @Autowired
    public RuntimeConsoleSchemaInitializer(JdbcTemplate jdbcTemplate,
                                           @Value("${springclaw.runtime-console.schema-auto-init:true}") boolean enabled) {
        this(jdbcTemplate, new ClassPathResource(MIGRATION_PATH), enabled);
    }

    RuntimeConsoleSchemaInitializer(JdbcTemplate jdbcTemplate, Resource migrationResource, boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrationResource = migrationResource;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            String sql = migrationResource.getContentAsString(StandardCharsets.UTF_8);
            for (String statement : splitSqlStatements(sql)) {
                jdbcTemplate.execute(statement);
            }
            ensureQualityColumns();
        } catch (Exception ex) {
            log.warn("Runtime Console trace schema initialization skipped, reason={}", ex.getMessage());
        }
    }

    static List<String> splitSqlStatements(String sql) {
        if (!StringUtils.hasText(sql)) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed) || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String statement = current.toString().trim();
                if (statement.endsWith(";")) {
                    statement = statement.substring(0, statement.length() - 1).trim();
                }
                if (StringUtils.hasText(statement)) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (StringUtils.hasText(tail)) {
            statements.add(tail);
        }
        return List.copyOf(statements);
    }

    private void ensureQualityColumns() {
        addColumnIfMissing("agent_run", "quality_score",
                "ALTER TABLE agent_run ADD COLUMN quality_score INT NULL AFTER total_tokens");
        addColumnIfMissing("agent_run", "quality_level",
                "ALTER TABLE agent_run ADD COLUMN quality_level VARCHAR(32) NULL AFTER quality_score");
        addColumnIfMissing("agent_run", "evaluation_json",
                "ALTER TABLE agent_run ADD COLUMN evaluation_json TEXT NULL AFTER quality_level");
        addColumnIfMissing("agent_run_step", "quality_score",
                "ALTER TABLE agent_run_step ADD COLUMN quality_score INT NULL AFTER duration_ms");
        addColumnIfMissing("agent_run_step", "quality_level",
                "ALTER TABLE agent_run_step ADD COLUMN quality_level VARCHAR(32) NULL AFTER quality_score");
    }

    private void addColumnIfMissing(String tableName, String columnName, String alterSql) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(1)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND column_name = ?
                        """,
                Integer.class,
                tableName,
                columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(alterSql);
        }
    }
}
