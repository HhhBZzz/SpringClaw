package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunTraceService.class);

    private final MessageEventService messageEventService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AgentRunTraceService(MessageEventService messageEventService,
                                ObjectMapper objectMapper,
                                ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(messageEventService, objectMapper, jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable());
    }

    public AgentRunTraceService(MessageEventService messageEventService, ObjectMapper objectMapper) {
        this(messageEventService, objectMapper, (JdbcTemplate) null);
    }

    AgentRunTraceService(MessageEventService messageEventService, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.messageEventService = messageEventService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public AgentRunTraceEvent record(String sessionKey,
                                     String channel,
                                     String userId,
                                     String requestId,
                                     String stepName,
                                     String type,
                                     String status,
                                     String detail,
                                     long durationMs) {
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                safe(requestId), safe(stepName), safe(type), safe(status), safe(detail), Math.max(0L, durationMs), System.currentTimeMillis()
        );
        if (StringUtils.hasText(sessionKey) && StringUtils.hasText(requestId)) {
            try {
                messageEventService.recordSingle(sessionKey, channel, userId, "SYSTEM", "TRACE", objectMapper.writeValueAsString(event), requestId);
            } catch (Exception ex) {
                log.warn("Agent trace 持久化失败，requestId={}, reason={}", requestId, ex.getMessage());
            }
        }
        recordStructuredTrace(sessionKey, channel, userId, requestId, event);
        return event;
    }

    public List<AgentRunTraceEvent> listTrace(String requestId, String userId, int limit) {
        if (!StringUtils.hasText(requestId)) {
            return List.of();
        }
        return messageEventService.listRequestEvents(requestId.trim(), userId, "SYSTEM", "TRACE", limit, true)
                .stream()
                .map(MessageEvent::getContent)
                .map(this::parseTrace)
                .filter(event -> event != null)
                .toList();
    }

    public List<Map<String, Object>> recentRuns(String userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return messageEventService.pageQuery(null, userId, "SYSTEM", "TRACE", 1, 500).getRecords().stream()
                .map(this::toRunRow)
                .filter(row -> !row.isEmpty())
                .collect(LinkedHashMap<String, Map<String, Object>>::new,
                        (map, row) -> map.putIfAbsent(String.valueOf(row.get("requestId")), row),
                        Map::putAll)
                .values()
                .stream()
                .limit(safeLimit)
                .toList();
    }

    private Map<String, Object> toRunRow(MessageEvent event) {
        AgentRunTraceEvent trace = parseTrace(event == null ? null : event.getContent());
        if (trace == null || !StringUtils.hasText(trace.requestId())) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("requestId", trace.requestId());
        row.put("sessionKey", event.getSessionKey());
        row.put("userId", event.getUserId());
        row.put("lastStep", trace.stepName());
        row.put("status", trace.status());
        row.put("detail", trace.detail());
        row.put("timestamp", trace.timestamp());
        return row;
    }

    private AgentRunTraceEvent parseTrace(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, AgentRunTraceEvent.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void recordStructuredTrace(String sessionKey,
                                       String channel,
                                       String userId,
                                       String requestId,
                                       AgentRunTraceEvent event) {
        if (jdbcTemplate == null || event == null || !StringUtils.hasText(requestId)) {
            return;
        }
        try {
            upsertAgentRun(sessionKey, channel, userId, requestId, event);
            insertRunStep(requestId, event);
            if ("tool".equalsIgnoreCase(event.type()) || "skill".equalsIgnoreCase(event.type())) {
                insertToolInvocation(sessionKey, userId, requestId, event);
            }
        } catch (Exception ex) {
            log.warn("结构化 Agent trace 写入失败，requestId={}, reason={}", requestId, ex.getMessage());
        }
    }

    private void upsertAgentRun(String sessionKey,
                                String channel,
                                String userId,
                                String requestId,
                                AgentRunTraceEvent event) {
        LocalDateTime now = LocalDateTime.now();
        String runStatus = toRunStatus(event);
        jdbcTemplate.update("""
                        INSERT INTO agent_run
                        (id, request_id, session_key, channel, user_id, response_mode, execution_mode, intent, status, started_at, finished_at, duration_ms, create_time, update_time, deleted)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, 0)
                        ON DUPLICATE KEY UPDATE
                          status = IF(VALUES(status) = 'RUNNING' AND status <> 'RUNNING', status, VALUES(status)),
                          finished_at = COALESCE(VALUES(finished_at), finished_at),
                          duration_ms = COALESCE(VALUES(duration_ms), duration_ms),
                          update_time = VALUES(update_time)
                        """,
                IdWorker.getId(),
                requestId,
                emptyToNull(sessionKey),
                defaultText(channel, "api"),
                defaultText(userId, "unknown"),
                runStatus,
                now,
                "RUNNING".equals(runStatus) ? null : now,
                "RUNNING".equals(runStatus) ? null : event.durationMs(),
                now,
                now);
    }

    private void insertRunStep(String requestId, AgentRunTraceEvent event) throws Exception {
        int sequenceNo = nextSequenceNo(requestId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                        INSERT INTO agent_run_step
                        (id, request_id, sequence_no, step_name, step_type, status, detail_json, started_at, finished_at, duration_ms, create_time, update_time, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                IdWorker.getId(),
                requestId,
                sequenceNo,
                defaultText(event.stepName(), "unknown"),
                defaultText(event.type(), "agent"),
                defaultText(event.status(), "success"),
                objectMapper.writeValueAsString(event),
                now,
                "started".equalsIgnoreCase(event.status()) ? null : now,
                event.durationMs(),
                now,
                now);
    }

    private void insertToolInvocation(String sessionKey,
                                      String userId,
                                      String requestId,
                                      AgentRunTraceEvent event) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                        INSERT INTO tool_invocation
                        (id, request_id, session_key, user_id, tool_name, skill_id, toolset, risk_level, status, duration_ms, input_summary, output_summary, error_message, create_time, update_time, deleted)
                        VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, NULL, ?, ?, ?, ?, 0)
                        """,
                IdWorker.getId(),
                requestId,
                emptyToNull(sessionKey),
                defaultText(userId, "unknown"),
                defaultText(event.stepName(), "unknown"),
                defaultText(event.type(), "tool"),
                defaultText(event.status(), "success"),
                event.durationMs(),
                truncate(event.detail(), 1024),
                "failed".equalsIgnoreCase(event.status()) ? truncate(event.detail(), 1024) : null,
                now,
                now);
    }

    private int nextSequenceNo(String requestId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM agent_run_step WHERE request_id = ?",
                Integer.class,
                requestId
        );
        return (max == null ? 0 : max) + 1;
    }

    private String toRunStatus(AgentRunTraceEvent event) {
        if (event == null) {
            return "RUNNING";
        }
        if ("final".equalsIgnoreCase(event.type())) {
            return "failed".equalsIgnoreCase(event.status()) ? "FAILED" : "COMPLETED";
        }
        return "failed".equalsIgnoreCase(event.status()) ? "FAILED" : "RUNNING";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String truncate(String text, int maxLen) {
        String value = safe(text);
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
