package com.springclaw.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentAssetPolicyTest {
    @Test
    void releaseComposeHasAFrontendAndPinnedRuntimeImages() throws IOException {
        String compose = read("docker-compose.yml");
        assertThat(compose).contains("frontend:");
        assertThat(compose).contains("context: ./frontend");
        assertThat(compose).contains("mysql:8.0.44");
        assertThat(compose).contains("redis:8.2.7");
        assertThat(compose).contains("rabbitmq:3.13.7-management");
        assertThat(compose).doesNotContain("redis/redis-stack-server:latest");
        assertThat(compose).doesNotContain("MYSQL_PASSWORD: ${MYSQL_ROOT_PASSWORD");
    }

    @Test
    void developmentOverlayOwnsLoopbackInfrastructurePorts() throws IOException {
        String base = read("docker-compose.yml");
        String development = read("docker-compose.dev.yml");
        assertThat(base).doesNotContain("MYSQL_EXPOSED_PORT");
        assertThat(base).doesNotContain("REDIS_EXPOSED_PORT");
        assertThat(development).contains("127.0.0.1:${MYSQL_EXPOSED_PORT:-3306}:3306");
        assertThat(development).contains("127.0.0.1:${REDIS_EXPOSED_PORT:-6379}:6379");
        assertThat(development).contains("127.0.0.1:${RABBITMQ_EXPOSED_PORT:-5672}:5672");
    }

    @Test
    void examplesAndRunbooksUseCanonicalVariablesAndFlyway() throws IOException {
        String env = read(".env.example");
        String docs = read("README.md") + read("README_CN.md") + read("RUN_REAL_ENVIRONMENT.md");
        assertThat(env).contains("MYSQL_PASSWORD=").contains("REDIS_PASSWORD=");
        assertThat(env).contains("SPRINGCLAW_ADMIN_USERNAMES=");
        assertThat(env).doesNotContain("MYSQL_USER=root");
        assertThat(docs).doesNotContain("OPENCLAW_").doesNotContain("schema.sql");
        assertThat(docs).contains("Flyway").contains("make verify");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), relativePath));
    }
}
