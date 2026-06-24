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

import java.nio.charset.StandardCharsets;

/**
 * Phase 3A1 memory core schema 初始化器。
 *
 * 启动时幂等执行 sql/migrations/2026-06-23-memory-core.sql：
 *   - 建 memory_record / memory_index_outbox；
 *   - 给 message_event 加 event_key 列、回填 legacy:&lt;id&gt;、加唯一键。
 *
 * migration 本身用 information-schema 守卫保证幂等；本类只负责加载与逐句执行。
 */
@Component
public class MemorySchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemorySchemaInitializer.class);
    private static final String MIGRATION_PATH = "sql/migrations/2026-06-23-memory-core.sql";

    private final JdbcTemplate jdbcTemplate;
    private final Resource migrationResource;
    private final boolean enabled;

    @Autowired
    public MemorySchemaInitializer(JdbcTemplate jdbcTemplate,
                                   @Value("${springclaw.memory.core.schema-auto-init:${springclaw.memory.schema-auto-init:true}}") boolean enabled) {
        this(jdbcTemplate, new ClassPathResource(MIGRATION_PATH), enabled);
    }

    MemorySchemaInitializer(JdbcTemplate jdbcTemplate, Resource migrationResource, boolean enabled) {
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
            if (migrationResource == null || !migrationResource.exists()) {
                throw new IllegalStateException(
                        "Memory core migration resource is missing: " + MIGRATION_PATH);
            }
            String sql = migrationResource.getContentAsString(StandardCharsets.UTF_8);
            for (String statement : RuntimeConsoleSchemaInitializer.splitSqlStatements(sql)) {
                jdbcTemplate.execute(statement);
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Memory core schema initialization failed", ex);
        }
    }
}
