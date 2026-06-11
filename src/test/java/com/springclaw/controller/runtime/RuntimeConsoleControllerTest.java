package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConsoleControllerTest {

    @AfterEach
    void clearContext() {
        RequestUserContextHolder.clear();
    }

    @Test
    void toolsShouldExposeMethodLevelCatalog() {
        SkillService skillService = mock(SkillService.class);
        when(skillService.resolveAllowedToolPacks("api", "user_local")).thenReturn(Set.of("web"));
        CapabilityRegistry registry = new CapabilityRegistry(List.of(new CapabilityRegistry.CapabilityEntry(
                RuntimeToolPack.class.getAnnotation(ToolPackDescriptor.class),
                new RuntimeToolPack(),
                "runtimeToolPack"
        )));
        RuntimeConsoleController controller = new RuntimeConsoleController(
                mock(SkillRegistryService.class),
                skillService,
                mock(ScheduledTaskService.class),
                mock(AiProviderService.class),
                mock(LlmUsageRecordService.class),
                mock(AgentRunTraceService.class),
                registry
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        ApiResponse<List<Map<String, Object>>> response = controller.tools();

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData())
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.get("name")).isEqualTo("lookup_weather");
                    assertThat(tool.get("packId")).isEqualTo("runtime-web");
                    assertThat(tool.get("runtimeToolName")).isEqualTo("RuntimeToolPack.lookup");
                    assertThat(tool.get("allow")).isEqualTo(true);
                    assertThat(tool.get("requiresConfirmation")).isEqualTo(false);
                });
    }

    @ToolPackDescriptor(
            id = "runtime-web",
            toolset = "web",
            triggerKeywords = {"天气"},
            riskLevel = "read",
            description = "运行时联网工具"
    )
    static class RuntimeToolPack {
        @Tool(name = "lookup_weather", description = "查询天气")
        public String lookup() {
            return "ok";
        }
    }
}
