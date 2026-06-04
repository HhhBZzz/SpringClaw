package com.springclaw.service.agent;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.pack.SystemHealthToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCapabilityExecutionServiceTest {

    @Test
    void shouldExecuteSystemHealthForModelControlRequests() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        WorkspaceReviewToolPack workspaceReviewToolPack = mock(WorkspaceReviewToolPack.class);
        WorkspaceSearchToolPack workspaceSearchToolPack = mock(WorkspaceSearchToolPack.class);
        LocalFilesystemToolPack localFilesystemToolPack = mock(LocalFilesystemToolPack.class);
        WebSearchToolPack webSearchToolPack = mock(WebSearchToolPack.class);
        WeatherToolPack weatherToolPack = mock(WeatherToolPack.class);
        NewsToolPack newsToolPack = mock(NewsToolPack.class);
        ExchangeRateToolPack exchangeRateToolPack = mock(ExchangeRateToolPack.class);
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
                weatherToolPack,
                newsToolPack,
                exchangeRateToolPack,
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

    @Test
    void shouldExecuteWeatherToolInsteadOfGenericWebForWeatherRequests() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        WorkspaceReviewToolPack workspaceReviewToolPack = mock(WorkspaceReviewToolPack.class);
        WorkspaceSearchToolPack workspaceSearchToolPack = mock(WorkspaceSearchToolPack.class);
        LocalFilesystemToolPack localFilesystemToolPack = mock(LocalFilesystemToolPack.class);
        WebSearchToolPack webSearchToolPack = mock(WebSearchToolPack.class);
        WeatherToolPack weatherToolPack = mock(WeatherToolPack.class);
        NewsToolPack newsToolPack = mock(NewsToolPack.class);
        ExchangeRateToolPack exchangeRateToolPack = mock(ExchangeRateToolPack.class);
        SkillLibraryToolPack skillLibraryToolPack = mock(SkillLibraryToolPack.class);
        ScriptSkillToolPack scriptSkillToolPack = mock(ScriptSkillToolPack.class);
        SystemToolPack systemToolPack = mock(SystemToolPack.class);
        SystemHealthToolPack systemHealthToolPack = mock(SystemHealthToolPack.class);
        when(weatherToolPack.queryWeather("北京")).thenReturn("城市: 北京\n来源: weather.com.cn\n温度: 28℃");

        AgentCapabilityExecutionService service = new AgentCapabilityExecutionService(
                aiProviderService,
                workspaceReviewToolPack,
                workspaceSearchToolPack,
                localFilesystemToolPack,
                webSearchToolPack,
                weatherToolPack,
                newsToolPack,
                exchangeRateToolPack,
                skillLibraryToolPack,
                scriptSkillToolPack,
                systemToolPack,
                systemHealthToolPack
        );

        List<AgentCapabilityResult> results = service.execute(
                new AgentDecision("web_research", "agent_tools", List.of("weather"), "read", false, "查询天气"),
                new AssembledContext("s1", "api", "u1", "今天北京天气怎样", "", "", ""),
                "req-1"
        );

        assertThat(results).extracting(AgentCapabilityResult::capabilityId)
                .containsExactly("weather.current");
        assertThat(results.get(0).payload()).contains("城市: 北京", "温度: 28℃");
        verify(weatherToolPack).queryWeather("北京");
        verify(webSearchToolPack, never()).webSearch(org.mockito.ArgumentMatchers.anyString());
    }
}
