package com.springclaw.tool.runtime.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageEventToolAuditServiceTest {

    @Test
    void shouldMirrorStructuredToolAuditIntoRunTraceWhenRequestIdExists() throws Exception {
        MessageEventService messageEventService = mock(MessageEventService.class);
        AgentRunTraceService agentRunTraceService = mock(AgentRunTraceService.class);
        MessageEventToolAuditService service = new MessageEventToolAuditService(messageEventService, agentRunTraceService);
        ToolExecutionContext context = new ToolExecutionContext("s1", "api", "u1", "req-1", "ACT-1");

        service.recordInvoke("WeatherToolPack.currentWeather", "SUCCESS", "北京晴 28C", context);

        ArgumentCaptor<String> auditPayload = ArgumentCaptor.forClass(String.class);
        verify(messageEventService).recordSingle(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("SYSTEM"),
                eq("TOOL"),
                auditPayload.capture(),
                eq("req-1")
        );
        Map<String, Object> content = new ObjectMapper().readValue(auditPayload.getValue(), new TypeReference<>() {
        });
        org.junit.jupiter.api.Assertions.assertEquals("springclaw.tool-audit.v1", content.get("schema"));
        org.junit.jupiter.api.Assertions.assertEquals("tool.invoke", content.get("eventType"));
        org.junit.jupiter.api.Assertions.assertEquals("WeatherToolPack.currentWeather", content.get("toolName"));
        org.junit.jupiter.api.Assertions.assertEquals("WeatherToolPack", content.get("toolset"));
        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS", content.get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("success", content.get("normalizedStatus"));
        org.junit.jupiter.api.Assertions.assertEquals("ACT-1", content.get("phase"));
        org.junit.jupiter.api.Assertions.assertEquals("北京晴 28C", content.get("detail"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "tool=WeatherToolPack.currentWeather, status=SUCCESS, phase=ACT-1, detail=北京晴 28C",
                content.get("summary")
        );

        ArgumentCaptor<String> tracePayload = ArgumentCaptor.forClass(String.class);
        verify(agentRunTraceService).record(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("req-1"),
                eq("WeatherToolPack.currentWeather"),
                eq("tool"),
                eq("success"),
                tracePayload.capture(),
                eq(0L)
        );
        Map<String, Object> trace = new ObjectMapper().readValue(tracePayload.getValue(), new TypeReference<>() {
        });
        org.junit.jupiter.api.Assertions.assertEquals("springclaw.tool-audit.v1", trace.get("schema"));
        org.junit.jupiter.api.Assertions.assertEquals("WeatherToolPack.currentWeather", trace.get("toolName"));
        org.junit.jupiter.api.Assertions.assertEquals("北京晴 28C", trace.get("detail"));
    }
}
