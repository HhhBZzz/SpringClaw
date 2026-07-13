package com.springclaw.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertThat(docs).contains("SPRINGCLAW_ADMIN_USERNAMES");
        assertThat(env).contains("MYSQL_PASSWORD=").contains("REDIS_PASSWORD=");
        assertThat(env).contains("SPRINGCLAW_ADMIN_USERNAMES=");
        assertThat(env).doesNotContain("MYSQL_USER=root");
        assertThat(docs).doesNotContain("OPENCLAW_").doesNotContain("schema.sql");
        assertThat(docs).contains("Flyway").contains("make verify");
    }

    @Test
    void webhookSecurityDeliveryContractIsExplicitAndSecretSafe() throws IOException {
        String compose = read("docker-compose.yml");
        String env = read(".env.example");
        String englishReadme = read("README.md");
        String chineseReadme = read("README_CN.md");
        String runbook = read("RUN_REAL_ENVIRONMENT.md");

        assertThat(compose)
                .containsPattern("(?m)^\\s+SPRINGCLAW_WEBHOOK_SECURITY_ENABLED: \\$\\{SPRINGCLAW_WEBHOOK_SECURITY_ENABLED:-false}\\s*$")
                .containsPattern("(?m)^\\s+SPRINGCLAW_WEBHOOK_SECRET: \\$\\{SPRINGCLAW_WEBHOOK_SECRET:-}\\s*$")
                .containsPattern("(?m)^\\s+SPRINGCLAW_WEBHOOK_SECRET_TELEGRAM: \\$\\{SPRINGCLAW_WEBHOOK_SECRET_TELEGRAM:-}\\s*$")
                .containsPattern("(?m)^\\s+SPRINGCLAW_WEBHOOK_SECRET_WECHAT: \\$\\{SPRINGCLAW_WEBHOOK_SECRET_WECHAT:-}\\s*$")
                .containsPattern("(?m)^\\s+SPRINGCLAW_WEBHOOK_SECRET_FEISHU: \\$\\{SPRINGCLAW_WEBHOOK_SECRET_FEISHU:-}\\s*$");
        assertThat(env).contains(
                "SPRINGCLAW_WEBHOOK_SECURITY_ENABLED=false",
                "SPRINGCLAW_WEBHOOK_SECRET=",
                "SPRINGCLAW_WEBHOOK_SECRET_TELEGRAM=",
                "SPRINGCLAW_WEBHOOK_SECRET_WECHAT=",
                "SPRINGCLAW_WEBHOOK_SECRET_FEISHU="
        );
        assertThat(englishReadme)
                .contains("publicly reachable inbound webhook")
                .contains("SPRINGCLAW_WEBHOOK_SECURITY_ENABLED=true")
                .contains("never log or commit");
        assertThat(chineseReadme)
                .contains("公网可访问的入站 Webhook")
                .contains("SPRINGCLAW_WEBHOOK_SECURITY_ENABLED=true")
                .contains("不得记录或提交");
        assertThat(runbook)
                .contains("公网可访问的入站 Webhook")
                .contains("SPRINGCLAW_WEBHOOK_SECURITY_ENABLED=true")
                .contains("不得记录或提交");
    }

    @Test
    void nativeLauncherForwardsTheDocumentedWebhookSecuritySettings() throws IOException {
        String launcher = read("scripts/run-native-backend.mjs");

        for (String setting : List.of(
                "SPRINGCLAW_WEBHOOK_SECURITY_ENABLED",
                "SPRINGCLAW_WEBHOOK_SECRET",
                "SPRINGCLAW_WEBHOOK_SECRET_TELEGRAM",
                "SPRINGCLAW_WEBHOOK_SECRET_WECHAT",
                "SPRINGCLAW_WEBHOOK_SECRET_FEISHU"
        )) {
            assertThat(launcher).contains("'" + setting + "',");
        }
    }

    @Test
    void dockerfilePinsBothJavaBaseImagesToImmutableDigests() throws IOException {
        String dockerfile = read("Dockerfile");
        List<String> javaFromLines = dockerfile.lines()
                .filter(line -> line.startsWith("FROM "))
                .filter(line -> line.contains("maven:") || line.contains("eclipse-temurin:"))
                .toList();

        assertThat(javaFromLines).containsExactly(
                "FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS builder",
                "FROM eclipse-temurin:17-jre@sha256:1824944ef1bd572d1ff0952afeb2fec7931d77c972c4fbc4dfcdf89f758fb490"
        );
        assertThat(javaFromLines).allMatch(line -> line.contains("@sha256:"));
    }

    @Test
    void verificationDocumentationLimitsProtectedApiResponsesToExpectedStatuses() throws IOException {
        assertThat(read("README.md"))
                .contains("only accepts 200, 401, or 403")
                .doesNotContain("returns an HTTP response");
        assertThat(read("README_CN.md"))
                .contains("仅接受 200、401 或 403")
                .doesNotContain("任意 HTTP 响应");
        assertThat(read("RUN_REAL_ENVIRONMENT.md"))
                .contains("仅接受 200、401 或 403")
                .doesNotContain("/api/auth/me` 代理响应");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), relativePath));
    }
}
