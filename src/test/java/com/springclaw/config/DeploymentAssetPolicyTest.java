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
    void nativeLauncherKeepsDeliveredPersistenceAndFeishuSafetySettings() throws IOException {
        String launcher = read("scripts/run-native-backend.mjs");

        assertThat(launcher).contains(
                "const requiredNativeRuntimeSafetySettings = {",
                "SPRINGCLAW_PERSISTENCE_DB_ENABLED: ['true'],",
                "SPRINGCLAW_FEISHU_OUTBOUND_ENABLED: ['true', 'false'],",
                "SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED: ['true', 'false'],"
        );
    }

    @Test
    void feishuDeliveryIsExplicitlyOptInAcrossDeliveryAssets() throws IOException {
        String compose = read("docker-compose.yml");
        String env = read(".env.example");
        String launcher = read("scripts/run-native-backend.mjs");
        String application = read("src/main/resources/application.yml");
        String englishReadme = read("README.md");
        String chineseReadme = read("README_CN.md");
        String runbook = read("RUN_REAL_ENVIRONMENT.md");
        String contributorGuide = read("CLAUDE.md");

        assertThat(compose)
                .containsPattern("(?m)^\\s+SPRINGCLAW_FEISHU_OUTBOUND_ENABLED: \\$\\{SPRINGCLAW_FEISHU_OUTBOUND_ENABLED:-false}\\s*$")
                .containsPattern("(?m)^\\s+SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED: \\$\\{SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED:-false}\\s*$");
        for (String credential : List.of(
                "SPRINGCLAW_FEISHU_APP_ID",
                "SPRINGCLAW_FEISHU_APP_SECRET",
                "SPRINGCLAW_FEISHU_VERIFICATION_TOKEN",
                "SPRINGCLAW_FEISHU_ENCRYPT_KEY",
                "SPRINGCLAW_FEISHU_DOMAIN"
        )) {
            assertThat(compose).containsPattern("(?m)^\\s+" + credential + ": \\$\\{" + credential + ":-}\\s*$");
            assertThat(env).contains(credential + "=");
            assertThat(launcher).contains("'" + credential + "',");
        }
        assertThat(env).contains(
                "SPRINGCLAW_FEISHU_OUTBOUND_ENABLED=false",
                "SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED=false"
        );
        assertThat(application).contains(
                "outbound-enabled: ${SPRINGCLAW_FEISHU_OUTBOUND_ENABLED:false}",
                "enabled: ${SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED:false}"
        );
        for (String documentation : List.of(englishReadme, chineseReadme, runbook, contributorGuide)) {
            assertThat(documentation)
                    .contains("SPRINGCLAW_FEISHU_OUTBOUND_ENABLED=true")
                    .contains("SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED=true")
                    .contains("SPRINGCLAW_FEISHU_APP_ID")
                    .contains("SPRINGCLAW_FEISHU_DOMAIN");
        }
        assertThat(englishReadme).contains("Feishu delivery is disabled by default");
        assertThat(chineseReadme).contains("飞书交付默认关闭");
        assertThat(runbook).contains("飞书交付默认关闭");
        assertThat(contributorGuide).contains("Feishu delivery is disabled by default");
    }

    @Test
    void nativeModeDocumentationRequiresABashCompatibleShell() throws IOException {
        assertThat(read("README.md")).contains("Bash-compatible shell");
        assertThat(read("README_CN.md")).contains("Bash 兼容 shell");
        assertThat(read("RUN_REAL_ENVIRONMENT.md")).contains("Bash 兼容 shell");
    }

    @Test
    void everyDockerBuildAndComposeImagePinsItsVersionTagToAnImmutableDigest() throws IOException {
        List<String> dockerfileFromLines = List.of(read("Dockerfile"), read("frontend/Dockerfile")).stream()
                .flatMap(dockerfile -> dockerfile.lines())
                .filter(line -> line.startsWith("FROM "))
                .toList();
        List<String> composeImageReferences = read("docker-compose.yml").lines()
                .map(String::trim)
                .filter(line -> line.startsWith("image:"))
                .map(line -> line.substring("image:".length()).trim())
                .toList();

        assertThat(dockerfileFromLines).allMatch(line -> line.matches(
                "FROM\\s+[^\\s]+:[^\\s]+@sha256:[a-f0-9]{64}(?:\\s+AS\\s+[^\\s]+)?"
        ));
        assertThat(composeImageReferences).allMatch(reference -> reference.matches(
                "[^\\s]+:[^\\s]+@sha256:[a-f0-9]{64}"
        ));
        assertThat(dockerfileFromLines).contains(
                "FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS builder",
                "FROM eclipse-temurin:17-jre@sha256:1824944ef1bd572d1ff0952afeb2fec7931d77c972c4fbc4dfcdf89f758fb490",
                "FROM node:22.18.0-alpine@sha256:1b2479dd35a99687d6638f5976fd235e26c5b37e8122f786fcd5fe231d63de5b AS builder",
                "FROM nginx:1.31.2-alpine@sha256:54f2a904c251d5a34adf545a72d32515a15e08418dae0266e23be2e18c66fefa"
        );
        assertThat(composeImageReferences).contains(
                "mysql:8.0.44@sha256:9c3380eac945af0736031b200027f581925927c81e010056214a4bd6b6693714",
                "redis:8.2.7@sha256:ccc02ba721bd2ddaf793bea8085c9079ce051f95a5142a2ac049a4b91954bd60",
                "rabbitmq:3.13.7-management@sha256:e582c0bc7766f3342496d8485efb5a1df782b5ce3886ad017e2eaae442311f69"
        );
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
