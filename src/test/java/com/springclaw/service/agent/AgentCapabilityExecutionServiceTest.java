package com.springclaw.service.agent;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.pack.SystemHealthToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCapabilityExecutionServiceTest {

    @Test
    void shouldExecuteSystemHealthForModelControlRequests() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        WorkspaceReviewToolPack workspaceReviewToolPack = mock(WorkspaceReviewToolPack.class);
        WorkspaceSearchToolPack workspaceSearchToolPack = mock(WorkspaceSearchToolPack.class);
        LocalFilesystemToolPack localFilesystemToolPack = mock(LocalFilesystemToolPack.class);
        WebSearchToolPack webSearchToolPack = mock(WebSearchToolPack.class);
        SkillLibraryToolPack skillLibraryToolPack = mock(SkillLibraryToolPack.class);
        ScriptSkillToolPack scriptSkillToolPack = mock(ScriptSkillToolPack.class);
        SystemToolPack systemToolPack = mock(SystemToolPack.class);
        SystemHealthToolPack systemHealthToolPack = mock(SystemHealthToolPack.class);

        when(aiProviderService.activeClient()).thenReturn(new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        ));
        when(systemHealthToolPack.runtimeHealth()).thenReturn("health=UP\ndb=UP\nredis=UP\nrabbit=UP");
        when(systemToolPack.jvmInfo()).thenReturn("JVM=HotSpot");
        when(systemToolPack.now()).thenReturn("2026-06-02T01:00:00+08:00");

        AgentCapabilityExecutionService service = new AgentCapabilityExecutionService(
                aiProviderService,
                workspaceReviewToolPack,
                workspaceSearchToolPack,
                localFilesystemToolPack,
                webSearchToolPack,
                skillLibraryToolPack,
                scriptSkillToolPack,
                systemToolPack,
                systemHealthToolPack
        );

        List<AgentCapabilityResult> results = service.execute(
                new AgentDecision("model_control", "agent_tools", List.of("system", "skill-library"), "read", false, "检查运行状态"),
                new AssembledContext("s1", "api", "u1", "检查后端启动、数据库、Redis、管理接口和模型配置是否正常。", "", "", ""),
                "req-1"
        );

        assertThat(results).extracting(AgentCapabilityResult::capabilityId)
                .contains("system-health", "model-status", "system.jvm", "system.time");
        assertThat(results.stream().map(AgentCapabilityResult::payload).toList())
                .anyMatch(payload -> payload.contains("db=UP") && payload.contains("redis=UP"));
    }
}
