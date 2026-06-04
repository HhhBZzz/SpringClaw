package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeCapabilityExecutorTest {

    @Test
    void shouldUseWeatherToolForWeatherCapability() {
        WeatherToolPack weatherToolPack = mock(WeatherToolPack.class);
        NewsToolPack newsToolPack = mock(NewsToolPack.class);
        ExchangeRateToolPack exchangeRateToolPack = mock(ExchangeRateToolPack.class);
        when(weatherToolPack.queryWeather("哈尔滨")).thenReturn("城市: 哈尔滨\n温度: -12°C\n来源: open-meteo");
        RealtimeCapabilityExecutor executor = new RealtimeCapabilityExecutor(weatherToolPack, newsToolPack, exchangeRateToolPack);

        List<CapabilityResult> results = executor.execute(
                new AgentDecision("web_research", "agent_tools", List.of("weather", "web"), "read", false, "天气查询"),
                new AssembledContext("s1", "api", "u1", "哈尔滨天气怎样", "", "", ""),
                "req-1"
        );

        assertThat(results).extracting(CapabilityResult::capabilityId).containsExactly("weather.current");
        assertThat(results.get(0).status()).isEqualTo("success");
        assertThat(results.get(0).payload()).contains("温度");
        verify(weatherToolPack).queryWeather("哈尔滨");
    }

    @Test
    void shouldTreatWeatherToolFailureTextAsFailedEvidence() {
        WeatherToolPack weatherToolPack = mock(WeatherToolPack.class);
        NewsToolPack newsToolPack = mock(NewsToolPack.class);
        ExchangeRateToolPack exchangeRateToolPack = mock(ExchangeRateToolPack.class);
        when(weatherToolPack.queryWeather("哈尔滨")).thenReturn("天气查询失败：已尝试 open-meteo、weather.com.cn，但都未返回有效结果");
        RealtimeCapabilityExecutor executor = new RealtimeCapabilityExecutor(weatherToolPack, newsToolPack, exchangeRateToolPack);

        List<CapabilityResult> results = executor.execute(
                new AgentDecision("web_research", "agent_tools", List.of("weather", "web"), "read", false, "天气查询"),
                new AssembledContext("s1", "api", "u1", "哈尔滨天气怎样", "", "", ""),
                "req-1"
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("failed");
        assertThat(results.get(0).payload()).contains("天气查询失败");
    }
}
