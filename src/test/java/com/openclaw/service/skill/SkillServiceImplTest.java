package com.openclaw.service.skill;

import com.openclaw.service.skill.impl.SkillServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class SkillServiceImplTest {

    @Test
    void shouldReturnDefaultToolPacksWhenDbDisabled() {
        SkillServiceImpl service = new SkillServiceImpl(
                null,
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
                true,
                false,
                true,
                true,
                true
        );

        String description = service.describeAvailableSkills("api", "u1");
        Assertions.assertTrue(description.contains("system-basic"));
        Assertions.assertTrue(description.contains("file-basic"));
        Assertions.assertTrue(description.contains("workspace-search"));
        Assertions.assertTrue(description.contains("web-basic"));
        Assertions.assertTrue(description.contains("weather-basic"));
        Assertions.assertTrue(description.contains("exchange-basic"));
        Assertions.assertTrue(description.contains("news-basic"));
        Assertions.assertTrue(description.contains("script-basic"));
    }

    @Test
    void shouldDescribeCoreSkillsWhenDbDisabled() {
        SkillServiceImpl service = new SkillServiceImpl(
                null,
                true,
                false,
                true,
                true,
                true
        );

        String description = service.describeCoreSkills("api", "u1");

        Assertions.assertTrue(description.contains("Workspace Explorer"));
        Assertions.assertTrue(description.contains("File Operator"));
        Assertions.assertTrue(description.contains("Web Research"));
        Assertions.assertTrue(description.contains("Runtime & System"));
        Assertions.assertTrue(description.contains("External Skills"));
        Assertions.assertFalse(description.contains("天气"));
        Assertions.assertFalse(description.contains("汇率"));
        Assertions.assertFalse(description.contains("新闻"));
    }
}
