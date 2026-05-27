package com.springclaw.frontend;

import com.springclaw.controller.HomeController;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VueOnlyFrontendPolicyTest {

    private final Path projectRoot = Path.of(System.getProperty("project.root", "")).toAbsolutePath().normalize();

    @Test
    void shouldNotKeepLegacySpringBootStaticFrontendPages() {
        assertThat(projectRoot.resolve("src/main/resources/static/agent/index.html"))
                .doesNotExist();
        assertThat(projectRoot.resolve("src/main/resources/static/admin/index.html"))
                .doesNotExist();
    }

    @Test
    void homeControllerShouldOnlyExposeVueFrontendEntries() {
        Map<String, Object> data = new HomeController().home().getData();

        assertThat(data).containsEntry("frontend", "Vue 3 + Vite");
        assertThat(data).doesNotContainKeys("staticAgentPage", "adminPage");
        assertThat(data.values()).doesNotContain("/agent/index.html", "/admin/index.html");
    }
}
