package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        verify(jdbcTemplate).update(startsWith("INSERT INTO tool_invocation"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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

    @Test
    void shouldPersistRunMetadataBeforeTraceStepsArrive() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, new ObjectMapper(), jdbcTemplate);

        service.recordRunMetadata("s1", "api", "u1", "req-1", "fast", "simplified", "general");

        verify(jdbcTemplate).update(
                argThat(sql -> sql.startsWith("INSERT INTO agent_run\n")
                        && sql.contains("product_mode")
                        && sql.contains("response_mode")
                        && sql.contains("execution_mode")
                        && sql.contains("intent")
                        && sql.contains("product_mode = COALESCE(NULLIF(VALUES(product_mode), ''), product_mode)")
                        && sql.contains("response_mode = COALESCE(NULLIF(VALUES(response_mode), ''), response_mode)")
                        && sql.contains("execution_mode = COALESCE(NULLIF(VALUES(execution_mode), ''), execution_mode)")
                        && sql.contains("intent = COALESCE(NULLIF(VALUES(intent), ''), intent)")),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void recentRunsShouldExposeProductModeFromStructuredRunMetadata() throws Exception {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, objectMapper, jdbcTemplate);
        MessageEvent event = new MessageEvent();
        event.setSessionKey("s1");
        event.setUserId("u1");
        event.setContent(objectMapper.writeValueAsString(new AgentRunTraceEvent(
                "req-1",
                "最终回答",
                "final",
                "success",
                "done",
                15L,
                1710000000000L,
                null,
                "",
                null
        )));
        Page<MessageEvent> page = new Page<>(1, 500);
        page.setRecords(List.of(event));
        when(messageEventService.pageQuery(null, null, "SYSTEM", "TRACE", 1, 500)).thenReturn(page);
        when(jdbcTemplate.queryForList(
                argThat(sql -> sql.contains("product_mode") && sql.contains("request_id = ?")),
                eq("req-1")
        )).thenReturn(List.of(Map.of("product_mode", "execution_task")));

        List<Map<String, Object>> runs = service.recentRuns(null, 10);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0)).containsEntry("productMode", "execution_task");
    }

    @Test
    void shouldExposeTimelineStepFieldsFromToolAuditTrace() throws Exception {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("req-1"))).thenReturn(0);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, objectMapper, jdbcTemplate);
        String detail = """
                {"schema":"springclaw.tool-audit.v1","eventType":"tool.invoke","toolName":"WorkspaceEditToolPack.workspaceRunCommand","toolset":"workspace","status":"FAILED","normalizedStatus":"failed","phase":"ACT-1","detail":"命令被拒绝","summary":"tool=WorkspaceEditToolPack.workspaceRunCommand, status=FAILED, phase=ACT-1, detail=命令被拒绝"}
                """;

        service.record("s1", "api", "u1", "req-1", "WorkspaceEditToolPack.workspaceRunCommand", "tool", "failed", detail, 12L);

        ArgumentCaptor<String> tracePayload = ArgumentCaptor.forClass(String.class);
        verify(messageEventService).recordSingle(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("SYSTEM"),
                eq("TRACE"),
                tracePayload.capture(),
                eq("req-1")
        );
        Map<String, Object> trace = objectMapper.readValue(tracePayload.getValue(), new TypeReference<>() {
        });
        assertThat(trace)
                .containsEntry("stepSchema", "springclaw.timeline-step.v1")
                .containsEntry("category", "tool")
                .containsEntry("action", "command.run")
                .containsEntry("target", "WorkspaceEditToolPack.workspaceRunCommand")
                .containsEntry("source", "workspace")
                .containsEntry("riskLevel", "write");
    }

    @Test
    void shouldUseToolAuditInputFieldsForTraceTargetAndToolInvocationInput() throws Exception {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("req-1"))).thenReturn(0);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, objectMapper, jdbcTemplate);
        String detail = """
                {"schema":"springclaw.tool-audit.v1","eventType":"tool.invoke","toolName":"WorkspaceEditToolPack.workspaceRunCommand","toolset":"workspace","status":"START","normalizedStatus":"started","phase":"ACT-1","detail":"","summary":"tool=WorkspaceEditToolPack.workspaceRunCommand, status=START, phase=ACT-1, detail=mvn test","action":"command.run","target":"mvn test","inputSummary":"mvn test"}
                """;

        service.record("s1", "api", "u1", "req-1", "WorkspaceEditToolPack.workspaceRunCommand", "tool", "started", detail, 0L);

        ArgumentCaptor<String> tracePayload = ArgumentCaptor.forClass(String.class);
        verify(messageEventService).recordSingle(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("SYSTEM"),
                eq("TRACE"),
                tracePayload.capture(),
                eq("req-1")
        );
        Map<String, Object> trace = objectMapper.readValue(tracePayload.getValue(), new TypeReference<>() {
        });
        assertThat(trace)
                .containsEntry("action", "command.run")
                .containsEntry("target", "mvn test")
                .containsEntry("source", "workspace")
                .containsEntry("riskLevel", "write");
        verify(jdbcTemplate).update(
                startsWith("INSERT INTO tool_invocation"),
                any(),
                eq("req-1"),
                eq("s1"),
                eq("u1"),
                eq("WorkspaceEditToolPack.workspaceRunCommand"),
                eq("workspace"),
                eq("started"),
                eq(0L),
                eq("mvn test"),
                isNull(),
                isNull(),
                any(),
                any()
        );
    }

    @Test
    void shouldPersistStructuredToolAuditFieldsIntoToolInvocation() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("req-1"))).thenReturn(0);
        AgentRunTraceService service = new AgentRunTraceService(messageEventService, new ObjectMapper(), jdbcTemplate);
        String detail = """
                {"schema":"springclaw.tool-audit.v1","eventType":"tool.invoke","toolName":"WeatherToolPack.currentWeather","toolset":"WeatherToolPack","status":"SUCCESS","normalizedStatus":"success","phase":"ACT-1","detail":"北京晴 28C","summary":"tool=WeatherToolPack.currentWeather, status=SUCCESS, phase=ACT-1, detail=北京晴 28C"}
                """;

        service.record("s1", "api", "u1", "req-1", "WeatherToolPack.currentWeather", "tool", "success", detail, 12L);

        verify(jdbcTemplate).update(
                startsWith("INSERT INTO tool_invocation"),
                any(),
                eq("req-1"),
                eq("s1"),
                eq("u1"),
                eq("WeatherToolPack.currentWeather"),
                eq("WeatherToolPack"),
                eq("success"),
                eq(12L),
                isNull(),
                eq("北京晴 28C"),
                isNull(),
                any(),
                any()
        );
    }
}
