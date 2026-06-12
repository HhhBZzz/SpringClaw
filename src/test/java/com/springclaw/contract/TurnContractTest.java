package com.springclaw.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.agent.AgentRunTraceEvent;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import com.springclaw.tool.runtime.impl.MessageEventToolAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 守护 docs/TURN_CONTRACT.md 的契约项。
 *
 * <p>每条测试对应契约里的一个 clause。当某条断言失败时，意味着 engine 链路
 * 已经偏离契约——需要先回答"为什么偏离"再考虑是否调整契约。
 *
 * <p>使用项目内现有的纯 Mockito 风格（参考 AgentRunTraceServiceTest /
 * MessageEventToolAuditServiceTest），不引入 Spring 集成测试基础设施。
 */
class TurnContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Build AgentRunTraceService with a mock JdbcTemplate via the public ObjectProvider constructor. */
    @SuppressWarnings("unchecked")
    private static AgentRunTraceService newTraceService(MessageEventService events, JdbcTemplate jdbc) {
        ObjectProvider<JdbcTemplate> provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new AgentRunTraceService(events, MAPPER, provider);
    }

    // ============================================================
    // §2.1 持久化 / §2.4 audit JSON schema
    // ============================================================

    @Nested
    @DisplayName("§2.4 tool audit JSON schema")
    class ToolAuditJsonSchemaTests {

        @Test
        @DisplayName("audit JSON 必含 §2.4 列出的所有字段（schema/eventType/toolName/toolset/status/normalizedStatus/phase/detail/summary/sessionKey/channel/userId/requestId）")
        void toolAuditJsonContainsAllRequiredFields() throws Exception {
            MessageEventService messageEventService = mock(MessageEventService.class);
            AgentRunTraceService traceService = mock(AgentRunTraceService.class);
            MessageEventToolAuditService service = new MessageEventToolAuditService(messageEventService, traceService, null, MAPPER);
            ToolExecutionContext ctx = new ToolExecutionContext("s-c1", "api", "u-c1", "req-c1", "ACT");

            service.recordInvoke("WeatherToolPack.currentWeather", "SUCCESS", "Beijing 28C", ctx);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageEventService).recordSingle(anyString(), anyString(), anyString(),
                    eq("SYSTEM"), eq("TOOL"), captor.capture(), anyString());
            Map<String, Object> payload = MAPPER.readValue(captor.getValue(), new TypeReference<>() {});
            assertThat(payload).containsKeys(
                    "schema", "eventType", "toolName", "toolset",
                    "status", "normalizedStatus", "phase", "detail", "summary",
                    "sessionKey", "channel", "userId", "requestId"
            );
            assertThat(payload.get("schema")).isEqualTo("springclaw.tool-audit.v1");
            assertThat(payload.get("eventType")).isEqualTo("tool.invoke");
        }

        @Test
        @DisplayName("toolset 字段应来自 ToolPackDescriptor.toolset()，不是类 simpleName（Phase A）")
        void toolAuditToolsetMatchesDescriptor() throws Exception {
            MessageEventService messageEventService = mock(MessageEventService.class);
            AgentRunTraceService traceService = mock(AgentRunTraceService.class);
            // 注册一个真实带 bean 的 CapabilityEntry，让 findToolsetByClassName 能查到 "web"
            CapabilityRegistry registry = new CapabilityRegistry(List.of(new CapabilityRegistry.CapabilityEntry(
                    SampleWebToolPack.class.getAnnotation(ToolPackDescriptor.class),
                    new SampleWebToolPack(),
                    "sampleWebToolPack"
            )));
            MessageEventToolAuditService service = new MessageEventToolAuditService(messageEventService, traceService, registry, MAPPER);
            ToolExecutionContext ctx = new ToolExecutionContext("s", "api", "u", "req", "ACT");

            service.recordInvoke("SampleWebToolPack.search", "SUCCESS", "ok", ctx);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageEventService).recordSingle(anyString(), anyString(), anyString(),
                    eq("SYSTEM"), eq("TOOL"), captor.capture(), anyString());
            Map<String, Object> payload = MAPPER.readValue(captor.getValue(), new TypeReference<>() {});
            assertThat(payload.get("toolset"))
                    .as("toolset 必须是 descriptor 里的 'web'，不是类名 'SampleWebToolPack'")
                    .isEqualTo("web");
        }

        @Test
        @DisplayName("registry 缺失时 toolset 回退到类名前缀（向后兼容）")
        void toolAuditToolsetFallsBackToClassNameWhenRegistryAbsent() throws Exception {
            MessageEventService messageEventService = mock(MessageEventService.class);
            // 不传 registry，走 fallback 路径
            MessageEventToolAuditService service = new MessageEventToolAuditService(messageEventService, null, null, MAPPER);
            ToolExecutionContext ctx = new ToolExecutionContext("s", "api", "u", "req", "ACT");

            service.recordInvoke("UnknownToolPack.doStuff", "SUCCESS", "ok", ctx);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageEventService).recordSingle(anyString(), anyString(), anyString(),
                    eq("SYSTEM"), eq("TOOL"), captor.capture(), anyString());
            Map<String, Object> payload = MAPPER.readValue(captor.getValue(), new TypeReference<>() {});
            assertThat(payload.get("toolset")).isEqualTo("UnknownToolPack");
        }

        @Test
        @DisplayName("context 非空时 audit JSON 的 requestId/sessionKey/userId 必须来自 context，不是占位符")
        void toolAuditPropagatesContextFromHolder() throws Exception {
            MessageEventService messageEventService = mock(MessageEventService.class);
            AgentRunTraceService traceService = mock(AgentRunTraceService.class);
            MessageEventToolAuditService service = new MessageEventToolAuditService(messageEventService, traceService, null, MAPPER);
            ToolExecutionContext ctx = new ToolExecutionContext("real-session", "feishu", "real-user", "real-req", "LOCAL-SHORTCUT");

            service.recordInvoke("SystemToolPack.now", "SUCCESS", "2026-06-12T00:00:00", ctx);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageEventService).recordSingle(eq("real-session"), eq("feishu"), eq("real-user"),
                    eq("SYSTEM"), eq("TOOL"), captor.capture(), eq("real-req"));
            Map<String, Object> payload = MAPPER.readValue(captor.getValue(), new TypeReference<>() {});
            assertThat(payload.get("sessionKey")).isEqualTo("real-session");
            assertThat(payload.get("channel")).isEqualTo("feishu");
            assertThat(payload.get("userId")).isEqualTo("real-user");
            assertThat(payload.get("requestId")).isEqualTo("real-req");
            assertThat(payload.get("phase")).isEqualTo("LOCAL-SHORTCUT");
        }

        @Test
        @DisplayName("DENIED 状态不能被偷换成 SUCCESS（§2.5）")
        void deniedToolDoesNotEmitSuccessStatus() throws Exception {
            MessageEventService messageEventService = mock(MessageEventService.class);
            AgentRunTraceService traceService = mock(AgentRunTraceService.class);
            MessageEventToolAuditService service = new MessageEventToolAuditService(messageEventService, traceService, null, MAPPER);
            ToolExecutionContext ctx = new ToolExecutionContext("s", "api", "u", "req-deny", "ACT");

            service.recordInvoke("WorkspaceEditToolPack.workspaceWriteFile", "DENIED",
                    "当前角色无权限调用工具", ctx);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageEventService).recordSingle(anyString(), anyString(), anyString(),
                    eq("SYSTEM"), eq("TOOL"), captor.capture(), anyString());
            Map<String, Object> payload = MAPPER.readValue(captor.getValue(), new TypeReference<>() {});
            assertThat(payload.get("status")).isEqualTo("DENIED");
            assertThat(payload.get("normalizedStatus")).isEqualTo("failed");
            // verify the trace forward also propagates failed status (not success)
            verify(traceService).record(anyString(), anyString(), anyString(), eq("req-deny"),
                    anyString(), eq("tool"), eq("failed"), anyString(), eq(0L));
        }
    }

    // ============================================================
    // §2.1-§2.2 持久化 & 事件序列（agent_run / agent_run_step）
    // ============================================================

    @Nested
    @DisplayName("§2.1-§2.2 agent_run / agent_run_step 持久化")
    class TraceTablePersistenceTests {

        @Test
        @DisplayName("recordRunMetadata 必须 INSERT 一行 agent_run，UPSERT 保证 requestId 唯一")
        void agentRunInsertedExactlyOncePerRequestId() {
            MessageEventService messageEventService = mock(MessageEventService.class);
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                    .thenReturn(0);
            AgentRunTraceService service = newTraceService(messageEventService, jdbcTemplate);

            service.recordRunMetadata("s-r1", "api", "u-r1", "req-r1",
                    "stream", "opar", "workspace_analysis");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));
            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("INSERT INTO agent_run");
            // ON DUPLICATE KEY UPDATE 保证同一 requestId 不会插入第二条
            assertThat(sql).containsIgnoringCase("ON DUPLICATE KEY UPDATE");
        }

        @Test
        @DisplayName("agent_run_step INSERT 时 sequence_no 必须从 nextSequenceNo 查出，不能硬编码 0/1")
        void stepSequenceNoMustBeMonotonic() {
            MessageEventService messageEventService = mock(MessageEventService.class);
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            // 模拟此前已经有 3 条 step
            when(jdbcTemplate.queryForObject(eq("SELECT COALESCE(MAX(sequence_no), 0) FROM agent_run_step WHERE request_id = ?"),
                    eq(Integer.class), eq("req-seq")))
                    .thenReturn(3);
            AgentRunTraceService service = newTraceService(messageEventService, jdbcTemplate);

            service.record("s", "api", "u", "req-seq", "act-step", "agent", "success", "ok", 100L);

            // 任何 INSERT INTO agent_run_step 调用必须带上从 MAX + 1 算出的 4
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(
                    org.mockito.ArgumentMatchers.contains("INSERT INTO agent_run_step"),
                    argsCaptor.capture()
            );
            // sequence_no 出现在参数列表中，应为 4（3+1）
            Object[] args = argsCaptor.getValue();
            boolean foundFour = false;
            for (Object arg : args) {
                if (arg instanceof Integer && (Integer) arg == 4) {
                    foundFour = true;
                    break;
                }
            }
            assertThat(foundFour)
                    .as("agent_run_step INSERT 参数应包含从 MAX+1 计算出的 sequence_no=4，args=%s",
                            java.util.Arrays.toString(args))
                    .isTrue();
        }

        @Test
        @DisplayName("空 requestId 时不能向 agent_run_step 写记录")
        void emptyRequestIdMustNotWriteStep() {
            MessageEventService messageEventService = mock(MessageEventService.class);
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            AgentRunTraceService service = newTraceService(messageEventService, jdbcTemplate);

            // requestId 为空字符串
            service.record("s", "api", "u", "", "step", "agent", "success", "ok", 100L);
            // requestId 为 null
            service.record("s", "api", "u", null, "step", "agent", "success", "ok", 100L);

            // 不应触发任何 agent_run_step INSERT
            verify(jdbcTemplate, never()).update(
                    org.mockito.ArgumentMatchers.contains("INSERT INTO agent_run_step"),
                    any(Object[].class)
            );
        }
    }

    // ============================================================
    // §3 行为契约 — replayRun 读 path
    // ============================================================

    @Nested
    @DisplayName("§2.1 replayRun 拼接三张表")
    class ReplayRunTests {

        @Test
        @DisplayName("agent_run 不存在时 replayRun 返回空 Map（对应 HTTP 404）")
        void replayRunReturnsEmptyWhenAgentRunMissing() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(jdbcTemplate.queryForList(anyString(), eq("missing")))
                    .thenReturn(List.of());
            AgentRunTraceService service = newTraceService(
                    mock(MessageEventService.class), jdbcTemplate);

            Map<String, Object> result = service.replayRun("missing");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("replayRun 必须按 sequence_no ASC 取 steps、按 create_time ASC 取 tool_invocations")
        void replayRunQueriesUseCorrectOrdering() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            Map<String, Object> runRow = new java.util.LinkedHashMap<>();
            runRow.put("request_id", "req-ok");
            runRow.put("status", "COMPLETED");
            when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("FROM agent_run WHERE request_id = ?"),
                    eq("req-ok"))).thenReturn(List.of(runRow));
            when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("FROM agent_run_step"),
                    eq("req-ok"))).thenReturn(List.of(Map.of("sequence_no", 1, "step_type", "route")));
            when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("FROM tool_invocation"),
                    eq("req-ok"))).thenReturn(List.of(Map.of("tool_name", "SystemToolPack.now")));
            AgentRunTraceService service = newTraceService(
                    mock(MessageEventService.class), jdbcTemplate);

            Map<String, Object> result = service.replayRun("req-ok");

            assertThat(result).containsKeys("request_id", "status", "steps", "toolInvocations");
            assertThat(result.get("request_id")).isEqualTo("req-ok");
            assertThat((List<?>) result.get("steps")).hasSize(1);
            assertThat((List<?>) result.get("toolInvocations")).hasSize(1);
            // verify the SQL strings include ORDER BY ... ASC
            verify(jdbcTemplate).queryForList(
                    org.mockito.ArgumentMatchers.contains("ORDER BY sequence_no ASC"),
                    eq("req-ok"));
            verify(jdbcTemplate).queryForList(
                    org.mockito.ArgumentMatchers.contains("ORDER BY create_time ASC"),
                    eq("req-ok"));
        }
    }

    // ============================================================
    // §3 行为契约 — final step 必填字段
    // ============================================================

    @Nested
    @DisplayName("§2.3 final step detail_json 必填字段")
    class FinalStepContractTests {

        @Test
        @DisplayName("AgentRunTraceEvent record 的 type='final' 是契约 §2.3 的承载点")
        void finalStepEventCarriesRequiredFields() {
            // 这条更像 schema 测试：保证 AgentRunTraceEvent record 字段集与契约对齐
            // 不需要数据库，只验证 type 字符串和 schema 形状
            AgentRunTraceEvent event = new AgentRunTraceEvent(
                    "req-f1",   // requestId
                    "final",    // stepName
                    "final",    // type
                    "success",  // status
                    "回答内容",  // detail
                    100L,       // durationMs
                    System.currentTimeMillis(), // timestamp
                    null,       // qualityScore
                    "",         // qualityLevel
                    ""          // evaluationJson
            );

            assertThat(event.requestId()).isEqualTo("req-f1");
            assertThat(event.type()).isEqualTo("final");
            assertThat(event.status()).isIn("success", "failed");
            assertThat(event.detail()).isNotBlank();
        }
    }

    // ============================================================
    // Helper tool pack used by toolset-resolution test
    // ============================================================

    @ToolPackDescriptor(
            id = "sample-web",
            toolset = "web",
            triggerKeywords = {"sample"},
            riskLevel = "read",
            description = "测试用 web tool pack"
    )
    static class SampleWebToolPack {
        @Tool(name = "search", description = "demo")
        public String search() {
            return "ok";
        }
    }
}
