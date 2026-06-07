package com.springclaw.service.agent;

import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCapabilityExecutionServiceTest {

    @Test
    void shouldDelegateCapabilityExecutionToRegistryAndAdaptResults() {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
        AgentCapabilityExecutionService service = new AgentCapabilityExecutionService(registry);
        AgentDecision decision = new AgentDecision("local_files", "agent_tools", List.of("local-files"), "read", false, "本地文件");
        AssembledContext assembled = new AssembledContext("s1", "api", "u1", "看看桌面", "", "", "");
        when(registry.execute(decision, assembled, "req-1")).thenReturn(List.of(
                new CapabilityResult(
                        "local-files.list-desktop",
                        "local-files",
                        "success",
                        "列出桌面目录文件",
                        "[F] root1:Desktop/demo.txt",
                        12L,
                        "read"
                )
        ));

        List<AgentCapabilityResult> results = service.execute(decision, assembled, "req-1");

        assertThat(results).containsExactly(new AgentCapabilityResult(
                "local-files.list-desktop",
                "success",
                "列出桌面目录文件",
                "[F] root1:Desktop/demo.txt"
        ));
        verify(registry).execute(decision, assembled, "req-1");
    }

    @Test
    void noopShouldReturnNoResults() {
        List<AgentCapabilityResult> results = AgentCapabilityExecutionService.noop()
                .execute(new AgentDecision("local_files", "agent_tools", List.of("local-files"), "read", false, ""), 
                        new AssembledContext("s1", "api", "u1", "看看桌面", "", "", ""),
                        "req-1");

        assertThat(results).isEmpty();
    }
}
