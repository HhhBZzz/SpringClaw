package com.openclaw.service.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.service.skill.impl.BuiltinSkillCatalogService;
import com.openclaw.service.skill.impl.SkillRegistryService;
import com.openclaw.service.skill.impl.SkillServiceImpl;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class SkillServiceImplTest {

    @Test
    void shouldReturnDefaultToolPacksWhenDbDisabled() {
        SkillServiceImpl service = new SkillServiceImpl(
                null,
                registryService(),
                true,
                false,
                true,
                true,
                true
        );

        Set<String> allowed = service.resolveAllowedToolPacks("api", "u1");
        Assertions.assertTrue(allowed.contains("system"));
        Assertions.assertTrue(allowed.contains("file"));
        Assertions.assertTrue(allowed.contains("workspace"));
        Assertions.assertTrue(allowed.contains("web"));
        Assertions.assertTrue(allowed.contains("weather"));
        Assertions.assertTrue(allowed.contains("exchange"));
        Assertions.assertTrue(allowed.contains("news"));
        Assertions.assertTrue(allowed.contains("script"));
    }

    @Test
    void shouldReturnEmptyWhenSkillDisabled() {
        SkillServiceImpl service = new SkillServiceImpl(
                null,
                registryService(),
                false,
                false,
                true,
                true,
                true
        );

        Set<String> allowed = service.resolveAllowedToolPacks("api", "u1");
        Assertions.assertTrue(allowed.isEmpty());
    }

    @Test
    void shouldDescribeDefaultsWhenDbDisabled() {
        SkillServiceImpl service = new SkillServiceImpl(
                null,
                registryService(),
                true,
                false,
                true,
                true,
                true
        );

        String description = service.describeAvailableSkills("api", "u1");
        Assertions.assertTrue(description.contains("code-analysis"));
        Assertions.assertTrue(description.contains("log-diagnostics"));
        Assertions.assertTrue(description.contains("系统基础能力"));
        Assertions.assertTrue(description.contains("天气能力"));
        Assertions.assertTrue(description.contains("汇率能力"));
        Assertions.assertTrue(description.contains("新闻能力"));
    }

    @Test
    void shouldDescribeCoreSkillsWhenDbDisabled() {
        SkillServiceImpl service = new SkillServiceImpl(
                null,
                registryService(),
                true,
                false,
                true,
                true,
                true
        );

        String description = service.describeCoreSkills("api", "u1");

        Assertions.assertTrue(description.contains("code-analysis"));
        Assertions.assertTrue(description.contains("log-diagnostics"));
        Assertions.assertTrue(description.contains("Web Research"));
        Assertions.assertTrue(description.contains("Runtime & System"));
        Assertions.assertFalse(description.contains("天气"));
        Assertions.assertFalse(description.contains("汇率"));
        Assertions.assertFalse(description.contains("新闻"));
    }

    private SkillRegistryService registryService() {
        return new SkillRegistryService(
                new BuiltinSkillCatalogService(),
                new ScriptSkillCatalogService(false, ".", "*", new ObjectMapper())
        );
    }
}
