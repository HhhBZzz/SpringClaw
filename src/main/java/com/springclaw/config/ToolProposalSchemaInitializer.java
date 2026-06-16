package com.springclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 启动时执行 tool_invocation_proposal 表 DDL。仿 RuntimeConsoleSchemaInitializer 风格，
 * 复用其 splitSqlStatements 工具方法。幂等：DDL 使用 CREATE TABLE IF NOT EXISTS。
 */
@Component
public class ToolProposalSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolProposalSchemaInitializer.class);
    private static final String MIGRATION_PATH = "sql/migrations/2026-06-17-tool-invocation-proposal.sql";

    private final JdbcTemplate jdbcTemplate;
    private final Resource migrationResource;
    private final boolean enabled;

    @Autowired
    public ToolProposalSchemaInitializer(JdbcTemplate jdbcTemplate,
                                         @Value("${springclaw.tool-proposal.schema-auto-init:true}") boolean enabled) {
        this(jdbcTemplate, new ClassPathResource(MIGRATION_PATH), enabled);
    }

    ToolProposalSchemaInitializer(JdbcTemplate jdbcTemplate, Resource migrationResource, boolean enabled) {
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
            for (String statement : RuntimeConsoleSchemaInitializer.splitSqlStatements(sql)) {
                jdbcTemplate.execute(statement);
            }
        } catch (Exception ex) {
            log.warn("Tool proposal schema initialization skipped, reason={}", ex.getMessage());
        }
    }
}
