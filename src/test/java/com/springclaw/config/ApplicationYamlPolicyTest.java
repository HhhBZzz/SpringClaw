package com.springclaw.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

class ApplicationYamlPolicyTest {

    @Test
    void defaultUserToolPolicyShouldAllowAuthorizedLocalFileReads() {
        Properties properties = applicationProperties();

        String denyTools = properties.getProperty("springclaw.auth.user-deny-tools", "");

        Assertions.assertFalse(denyTools.contains("LocalFilesystemToolPack"));
        Assertions.assertFalse(denyTools.contains("FileToolPack.*"));
        Assertions.assertTrue(denyTools.contains("SystemToolPack.runCommand"));
        Assertions.assertTrue(denyTools.contains("FileToolPack.writeTextFile"));
        Assertions.assertTrue(denyTools.contains("WorkspaceEditToolPack.*"));
    }

    @Test
    void runtimeMemoryDataShouldNotDefaultToDocsDirectory() {
        Properties properties = applicationProperties();

        String bankRoot = properties.getProperty("springclaw.memory.bank-root", "");
        String learningRoot = properties.getProperty("springclaw.learning.root", "");

        Assertions.assertTrue(bankRoot.contains("data/memory-bank"));
        Assertions.assertTrue(learningRoot.contains("data/memory-bank"));
        Assertions.assertFalse(bankRoot.contains("docs/memory-bank"));
        Assertions.assertFalse(learningRoot.contains("docs/memory-bank"));
    }

    @Test
    void memoryConsolidationAutoTriggerShouldHaveExplicitRollbackProperties() {
        Properties properties = applicationProperties();

        Assertions.assertEquals(
                "${SPRINGCLAW_MEMORY_CONSOLIDATION_AUTO_ENABLED:true}",
                properties.getProperty("springclaw.memory.consolidation.auto-enabled")
        );
        Assertions.assertEquals(
                "${SPRINGCLAW_MEMORY_CONSOLIDATION_AUTO_EPISODE_LIMIT:50}",
                properties.getProperty("springclaw.memory.consolidation.auto-episode-limit")
        );
    }

    @Test
    void providerMemoryEvaluationHarnessShouldDefaultToDisabled() {
        Properties properties = applicationProperties();

        Assertions.assertEquals(
                "${SPRINGCLAW_MEMORY_EVALUATION_PROVIDER_HARNESS_ENABLED:false}",
                properties.getProperty("springclaw.memory.evaluation.provider-harness-enabled")
        );
    }

    @Test
    void deploymentDefaultsUseSpringclawFlywayAndConfigurableWebhookSecurity() {
        Properties properties = applicationProperties();

        Assertions.assertTrue(properties.getProperty("spring.datasource.url").contains("${MYSQL_DB:springclaw}"));
        Assertions.assertEquals("true", properties.getProperty("spring.flyway.validate-on-migrate"));
        Assertions.assertEquals("true", properties.getProperty("spring.flyway.clean-disabled"));
        Assertions.assertEquals("${SPRINGCLAW_WEBHOOK_SECURITY_ENABLED:false}",
                properties.getProperty("springclaw.webhook.security.enabled"));
    }

    @Test
    void bootstrapAdminIsConfigurableForNativeDevelopmentAndDisabledByDefaultInProduction() {
        Properties baseProperties = applicationProperties();
        Properties productionProperties = applicationProperties("application-prod.yml");

        Assertions.assertEquals(
                "${SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN:true}",
                baseProperties.getProperty("springclaw.auth.bootstrap-first-user-admin")
        );
        Assertions.assertEquals(
                "${SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN:false}",
                productionProperties.getProperty("springclaw.auth.bootstrap-first-user-admin")
        );
    }

    private Properties applicationProperties() {
        return applicationProperties("application.yml");
    }

    private Properties applicationProperties(String resourceName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        return factory.getObject();
    }
}
