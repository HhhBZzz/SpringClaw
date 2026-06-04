package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunTraceServiceTest {

    @Test
    void shouldMirrorTraceEventIntoStructuredRuntimeTables() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("req-1"))).thenReturn(0);
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, new ObjectMapper(), jdbcTemplate);

        service.record("s1", "api", "u1", "req-1", "weather.current", "tool", "success", "查询实时天气", 12L);

        verify(messageEventService).recordSingle(eq("s1"), eq("api"), eq("u1"), eq("SYSTEM"), eq("TRACE"), any(String.class), eq("req-1"));
        verify(jdbcTemplate).update(argThat(sql -> sql.startsWith("INSERT INTO agent_run\n")), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate).update(startsWith("INSERT INTO agent_run_step"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate).update(startsWith("INSERT INTO tool_invocation"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldPersistQualityScoreIntoTraceAndRuntimeRun() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("req-1"))).thenReturn(0);
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, new ObjectMapper(), jdbcTemplate);
        AgentQualityScore quality = new AgentQualityScore(
                88, 95, 92, 90, 86, 82, 96, 100,
                "strong",
                "天气工具成功执行，证据足够。",
                java.util.List.of("weather 工具成功执行", "证据包含实时温度")
        );

        service.record("s1", "api", "u1", "req-1", "校验证据", "verification", "success", "证据通过", 12L, quality);

        verify(messageEventService).recordSingle(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("SYSTEM"),
                eq("TRACE"),
                argThat(json -> json.contains("\"qualityScore\":88") && json.contains("\"qualityLevel\":\"strong\"")),
                eq("req-1")
        );
        verify(jdbcTemplate).update(
                argThat(sql -> sql.startsWith("INSERT INTO agent_run\n") && sql.contains("quality_score") && sql.contains("quality_level") && sql.contains("evaluation_json")),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
        verify(jdbcTemplate).update(
                argThat(sql -> sql.startsWith("INSERT INTO agent_run_step") && sql.contains("quality_score") && sql.contains("quality_level")),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }
}
