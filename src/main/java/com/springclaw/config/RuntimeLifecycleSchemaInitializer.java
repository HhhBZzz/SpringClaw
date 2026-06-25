package com.springclaw.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(
        prefix = "springclaw.runtime.lifecycle",
        name = "store",
        havingValue = "mysql"
)
public class RuntimeLifecycleSchemaInitializer implements ApplicationRunner {

    private static final String MIGRATION_PATH =
            "sql/migrations/2026-06-25-runtime-run-lifecycle.sql";

    private final JdbcTemplate jdbcTemplate;
    private final Resource migrationResource;
    private final boolean enabled;

    @Autowired
    public RuntimeLifecycleSchemaInitializer(
            JdbcTemplate jdbcTemplate,
            @Value("${springclaw.runtime.lifecycle.schema-auto-init:true}")
            boolean enabled
    ) {
        this(jdbcTemplate, new ClassPathResource(MIGRATION_PATH), enabled);
    }

    RuntimeLifecycleSchemaInitializer(
            JdbcTemplate jdbcTemplate,
            Resource migrationResource,
            boolean enabled
    ) {
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
                        "Runtime lifecycle migration resource is missing: "
                                + MIGRATION_PATH
                );
            }
            String sql = migrationResource.getContentAsString(StandardCharsets.UTF_8);
            for (String statement : RuntimeConsoleSchemaInitializer.splitSqlStatements(sql)) {
                jdbcTemplate.execute(statement);
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Runtime lifecycle schema initialization failed",
                    ex
            );
        }
    }
}
