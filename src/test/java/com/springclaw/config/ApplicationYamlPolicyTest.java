package com.springclaw.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

class ApplicationYamlPolicyTest {

    @Test
    void defaultUserToolPolicyShouldAllowAuthorizedLocalFileReads() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();

        String denyTools = properties.getProperty("springclaw.auth.user-deny-tools", "");

        Assertions.assertFalse(denyTools.contains("LocalFilesystemToolPack"));
        Assertions.assertFalse(denyTools.contains("FileToolPack.*"));
        Assertions.assertTrue(denyTools.contains("SystemToolPack.runCommand"));
        Assertions.assertTrue(denyTools.contains("FileToolPack.writeTextFile"));
        Assertions.assertTrue(denyTools.contains("WorkspaceEditToolPack.*"));
    }
}
