package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.memory.evaluation.MemoryUsageTraceReader;
import com.springclaw.service.memory.evaluation.MemoryUsageTraceView;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
                registry,
                mock(MemoryUsageTraceReader.class)
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

    @Test
    void runMemoryUsageShouldReadAdminScopedTraceWithoutUserFilter() {
        MemoryUsageTraceReader reader = mock(MemoryUsageTraceReader.class);
        RuntimeConsoleController controller = controllerWithMemoryUsage(reader);
        MemoryUsageTraceView view = new MemoryUsageTraceView(
                "run-1",
                true,
                true,
                "EXPLICIT",
                "deterministic",
                List.of("pref-1"),
                "event-memory-usage",
                null
        );
        when(reader.readLatest("run-1", null)).thenReturn(view);
        RequestUserContextHolder.set(new RequestUserContext(
                "admin",
                "ADMIN",
                System.currentTimeMillis() + 60_000
        ));

        ApiResponse<MemoryUsageTraceView> response = controller.runMemoryUsage("run-1");

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(view);
        verify(reader).readLatest("run-1", null);
    }

    @Test
    void runMemoryUsageShouldUseCurrentUserFilterForNonAdmin() {
        MemoryUsageTraceReader reader = mock(MemoryUsageTraceReader.class);
        RuntimeConsoleController controller = controllerWithMemoryUsage(reader);
        when(reader.readLatest("run-2", "alice"))
                .thenReturn(MemoryUsageTraceView.empty("run-2"));
        RequestUserContextHolder.set(new RequestUserContext(
                "alice",
                "USER",
                System.currentTimeMillis() + 60_000
        ));

        ApiResponse<MemoryUsageTraceView> response = controller.runMemoryUsage("run-2");

        assertThat(response.getData().requestId()).isEqualTo("run-2");
        verify(reader).readLatest("run-2", "alice");
    }

    @Test
    void runMemoryUsageShouldRejectBlankRequestId() {
        RuntimeConsoleController controller = controllerWithMemoryUsage(mock(MemoryUsageTraceReader.class));
        RequestUserContextHolder.set(new RequestUserContext(
                "alice",
                "USER",
                System.currentTimeMillis() + 60_000
        ));

        assertThrows(BusinessException.class, () -> controller.runMemoryUsage(" "));
    }

    private RuntimeConsoleController controllerWithMemoryUsage(MemoryUsageTraceReader reader) {
        return new RuntimeConsoleController(
                mock(SkillRegistryService.class),
                mock(SkillService.class),
                mock(ScheduledTaskService.class),
                mock(AiProviderService.class),
                mock(LlmUsageRecordService.class),
                mock(AgentRunTraceService.class),
                new CapabilityRegistry(List.of()),
                reader
        );
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
