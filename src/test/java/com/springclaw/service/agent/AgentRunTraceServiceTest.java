package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
        verify(jdbcTemplate).update(startsWith("INSERT INTO agent_run"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate).update(startsWith("INSERT INTO agent_run_step"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate).update(startsWith("INSERT INTO tool_invocation"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
