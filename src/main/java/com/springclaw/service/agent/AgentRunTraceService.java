package com.springclaw.service.agent;

import com.springclaw.common.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.AgentLearningService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AgentRunTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunTraceService.class);
    private static final String TOOL_AUDIT_SCHEMA = "springclaw.tool-audit.v1";

    private final MessageEventService messageEventService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AgentLearningService agentLearningService;
    private final RunStateRepository runStateRepository;

    @Autowired
    public AgentRunTraceService(MessageEventService messageEventService,
                                ObjectMapper objectMapper,
                                ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                ObjectProvider<AgentLearningService> agentLearningServiceProvider,
                                RunStateRepository runStateRepository) {
        this(messageEventService,
                objectMapper,
                jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable(),
                agentLearningServiceProvider == null ? null : agentLearningServiceProvider.getIfAvailable(),
                runStateRepository);
    }

    public AgentRunTraceService(MessageEventService messageEventService,
                                ObjectMapper objectMapper,
                                ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(messageEventService, objectMapper, jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable(), null, null);
    }

    public AgentRunTraceService(MessageEventService messageEventService, ObjectMapper objectMapper) {
        this(messageEventService, objectMapper, (JdbcTemplate) null, null, null);
    }

    AgentRunTraceService(MessageEventService messageEventService, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this(messageEventService, objectMapper, jdbcTemplate, null, null);
    }

    AgentRunTraceService(MessageEventService messageEventService,
                         ObjectMapper objectMapper,
                         JdbcTemplate jdbcTemplate,
                         AgentLearningService agentLearningService) {
        this(messageEventService, objectMapper, jdbcTemplate, agentLearningService, null);
    }

    AgentRunTraceService(MessageEventService messageEventService,
                         ObjectMapper objectMapper,
                         JdbcTemplate jdbcTemplate,
                         AgentLearningService agentLearningService,
                         RunStateRepository runStateRepository) {
        this.messageEventService = messageEventService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.agentLearningService = agentLearningService;
        this.runStateRepository = runStateRepository;
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
        return record(sessionKey, channel, userId, requestId, stepName, type, status, detail, durationMs, null);
    }

    public AgentRunTraceEvent record(String sessionKey,
                                     String channel,
                                     String userId,
                                     String requestId,
                                     String stepName,
                                     String type,
                                     String status,
                                     String detail,
                                     long durationMs,
                                     AgentQualityScore quality) {
        AgentRunTraceEvent event = buildTraceEvent(requestId, stepName, type, status, detail, durationMs, quality);
        if (StringUtils.hasText(sessionKey) && StringUtils.hasText(requestId)) {
            try {
                messageEventService.recordSingle(sessionKey, channel, userId, "SYSTEM", "TRACE", objectMapper.writeValueAsString(event), requestId);
            } catch (Exception ex) {
                log.warn("Agent trace 持久化失败，requestId={}, reason={}", requestId, ex.getMessage());
            }
        }
        recordStructuredTrace(sessionKey, channel, userId, requestId, event);
        captureLearning(event);
        return event;
    }

    private void captureLearning(AgentRunTraceEvent event) {
        if (agentLearningService == null || event == null) {
            return;
        }
        try {
            agentLearningService.captureTraceFailure(event);
        } catch (Exception ex) {
            log.debug("Agent learning capture skipped, requestId={}, reason={}", event.requestId(), ex.getMessage());
        }
    }

    private AgentRunTraceEvent buildTraceEvent(String requestId,
                                               String stepName,
                                               String type,
                                               String status,
                                               String detail,
                                               long durationMs,
                                               AgentQualityScore quality) {
        TimelineStepDetail timelineDetail = parseTimelineStepDetail(detail);
        ToolAuditDetail auditDetail = timelineDetail == null ? parseToolAuditDetail(detail) : null;
        String category = timelineDetail == null ? defaultText(type, "agent") : defaultText(timelineDetail.category(), type);
        String action = timelineDetail == null
                ? auditDetail == null ? defaultText(type, "agent") : resolveToolAction(auditDetail)
                : defaultText(timelineDetail.action(), category);
        String target = timelineDetail == null
                ? auditDetail == null ? defaultText(stepName, type) : defaultText(auditDetail.target(), defaultText(auditDetail.toolName(), stepName))
                : defaultText(timelineDetail.target(), stepName);
        String source = timelineDetail == null
                ? auditDetail == null ? "" : defaultText(auditDetail.toolset(), "")
                : defaultText(timelineDetail.source(), "");
        String riskLevel = timelineDetail == null
                ? inferRiskLevel(category, target, detail)
                : defaultText(timelineDetail.riskLevel(), inferRiskLevel(category, target, detail));
        return new AgentRunTraceEvent(
                TextUtils.safe(requestId),
                TextUtils.safe(stepName),
                TextUtils.safe(type),
                TextUtils.safe(status),
                TextUtils.safe(detail),
                Math.max(0L, durationMs),
                System.currentTimeMillis(),
                quality == null ? null : quality.overallScore(),
                quality == null ? "" : quality.level(),
                serializeQuality(quality),
                AgentRunTraceEvent.TIMELINE_STEP_SCHEMA,
                category,
                action,
                target,
                source,
                riskLevel
        );
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

    public void recordRunMetadata(String sessionKey,
                                  String channel,
                                  String userId,
                                  String requestId,
                                  String responseMode,
                                  String executionMode,
                                  String intent) {
        recordRunMetadata(sessionKey, channel, userId, requestId, responseMode, executionMode, intent, null);
    }

    public void recordRunMetadata(String sessionKey,
                                  String channel,
                                  String userId,
                                  String requestId,
                                  String responseMode,
                                  String executionMode,
                                  String intent,
                                  String productMode) {
        if (jdbcTemplate == null || !StringUtils.hasText(requestId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        RunState canonical = canonicalRun(requestId);
        String canonicalStatus = canonicalStatus(canonical);
        LocalDateTime canonicalStartedAt = canonicalStart(canonical);
        try {
            jdbcTemplate.update("""
                            INSERT INTO agent_run
                            (id, request_id, session_key, channel, user_id, product_mode, response_mode, execution_mode, intent, status, started_at, create_time, update_time, deleted)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                            ON DUPLICATE KEY UPDATE
                              session_key = COALESCE(NULLIF(VALUES(session_key), ''), session_key),
                              channel = COALESCE(NULLIF(VALUES(channel), ''), channel),
                              user_id = COALESCE(NULLIF(VALUES(user_id), ''), user_id),
                              product_mode = COALESCE(NULLIF(VALUES(product_mode), ''), product_mode),
                              response_mode = COALESCE(NULLIF(VALUES(response_mode), ''), response_mode),
                              execution_mode = COALESCE(NULLIF(VALUES(execution_mode), ''), execution_mode),
                              intent = COALESCE(NULLIF(VALUES(intent), ''), intent),
                              update_time = VALUES(update_time)
                            """,
                    IdWorker.getId(),
                    requestId.trim(),
                    emptyToNull(sessionKey),
                    defaultText(channel, "api"),
                    defaultText(userId, "unknown"),
                    emptyToNull(productMode),
                    emptyToNull(responseMode),
                    emptyToNull(executionMode),
                    emptyToNull(intent),
                    canonicalStatus,
                    canonicalStartedAt == null ? now : canonicalStartedAt,
                    now,
                    now);
        } catch (Exception ex) {
            log.warn("Agent run 元数据写入失败，requestId={}, reason={}", requestId, ex.getMessage());
        }
    }

    public List<Map<String, Object>> recentRuns(String userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = messageEventService.pageQuery(null, userId, "SYSTEM", "TRACE", 1, 500).getRecords().stream()
                .map(this::toRunRow)
                .filter(row -> !row.isEmpty())
                .collect(LinkedHashMap<String, Map<String, Object>>::new,
                        (map, row) -> map.putIfAbsent(String.valueOf(row.get("requestId")), row),
                        Map::putAll)
                .values()
                .stream()
                .limit(safeLimit)
                .toList();
        enrichProductMode(rows);
        return rows;
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

    private void enrichProductMode(List<Map<String, Object>> rows) {
        if (jdbcTemplate == null || rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            String requestId = text(row, "requestId");
            if (!StringUtils.hasText(requestId)) {
                continue;
            }
            try {
                List<Map<String, Object>> metadataRows = jdbcTemplate.queryForList(
                        "SELECT product_mode FROM agent_run WHERE request_id = ? AND deleted = 0",
                        requestId
                );
                if (metadataRows.isEmpty()) {
                    continue;
                }
                String productMode = text(metadataRows.get(0), "product_mode", "productMode");
                if (StringUtils.hasText(productMode)) {
                    row.put("productMode", productMode);
                }
            } catch (Exception ex) {
                log.debug("Agent run 产品模式补充失败，requestId={}, reason={}", requestId, ex.getMessage());
            }
        }
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
        RunState canonical = canonicalRun(requestId);
        String runStatus = canonicalStatus(canonical);
        LocalDateTime startedAt = canonicalStart(canonical);
        LocalDateTime finishedAt = canonicalTime(
                canonical == null ? null : canonical.finishedAt()
        );
        Long durationMs = canonicalDurationMs(canonical);
        jdbcTemplate.update("""
                        INSERT INTO agent_run
                        (id, request_id, session_key, channel, user_id, response_mode, execution_mode, intent, status, started_at, finished_at, duration_ms, quality_score, quality_level, evaluation_json, create_time, update_time, deleted)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        ON DUPLICATE KEY UPDATE
                          status = IF(
                            (VALUES(status) = 'RUNNING' AND status <> 'RUNNING')
                            OR (VALUES(status) = 'UNKNOWN'
                                AND status IN ('COMPLETED', 'DEGRADED', 'FAILED')),
                            status,
                            VALUES(status)
                          ),
                          finished_at = COALESCE(VALUES(finished_at), finished_at),
                          duration_ms = COALESCE(VALUES(duration_ms), duration_ms),
                          quality_score = COALESCE(VALUES(quality_score), quality_score),
                          quality_level = COALESCE(NULLIF(VALUES(quality_level), ''), quality_level),
                          evaluation_json = COALESCE(NULLIF(VALUES(evaluation_json), ''), evaluation_json),
                          update_time = VALUES(update_time)
                        """,
                IdWorker.getId(),
                requestId,
                emptyToNull(sessionKey),
                defaultText(channel, "api"),
                defaultText(userId, "unknown"),
                runStatus,
                startedAt == null ? now : startedAt,
                finishedAt,
                durationMs,
                event.qualityScore(),
                emptyToNull(event.qualityLevel()),
                emptyToNull(event.evaluationJson()),
                now,
                now);
    }

    private void insertRunStep(String requestId, AgentRunTraceEvent event) throws Exception {
        int sequenceNo = nextSequenceNo(requestId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                        INSERT INTO agent_run_step
                        (id, request_id, sequence_no, step_name, step_type, status, detail_json, started_at, finished_at, duration_ms, quality_score, quality_level, create_time, update_time, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
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
                event.qualityScore(),
                emptyToNull(event.qualityLevel()),
                now,
                now);
    }

    private void insertToolInvocation(String sessionKey,
                                      String userId,
                                      String requestId,
                                      AgentRunTraceEvent event) {
        LocalDateTime now = LocalDateTime.now();
        ToolAuditDetail auditDetail = parseToolAuditDetail(event.detail());
        String status = auditDetail == null ? defaultText(event.status(), "success") : defaultText(auditDetail.normalizedStatus(), event.status());
        String inputSummary = inputSummary(auditDetail, status);
        String outputSummary = outputSummary(auditDetail, event.detail(), status);
        String errorMessage = errorSummary(auditDetail, event.detail(), status);
        jdbcTemplate.update("""
                        INSERT INTO tool_invocation
                        (id, request_id, session_key, user_id, tool_name, skill_id, toolset, risk_level, status, duration_ms, input_summary, output_summary, error_message, create_time, update_time, deleted)
                        VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                IdWorker.getId(),
                requestId,
                emptyToNull(sessionKey),
                defaultText(userId, "unknown"),
                defaultText(auditDetail == null ? null : auditDetail.toolName(), event.stepName()),
                defaultText(auditDetail == null ? null : auditDetail.toolset(), event.type()),
                status,
                event.durationMs(),
                truncateOrNull(inputSummary, 512),
                truncateOrNull(outputSummary, 1024),
                truncateOrNull(errorMessage, 1024),
                now,
                now);
    }

    private ToolAuditDetail parseToolAuditDetail(String detail) {
        if (!StringUtils.hasText(detail) || !detail.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(detail, new TypeReference<>() {
            });
            if (!TOOL_AUDIT_SCHEMA.equals(text(payload, "schema"))) {
                return null;
            }
            return new ToolAuditDetail(
                    text(payload, "eventType"),
                    text(payload, "toolName"),
                    text(payload, "toolset"),
                    text(payload, "status"),
                    text(payload, "normalizedStatus"),
                    text(payload, "phase"),
                    text(payload, "detail"),
                    text(payload, "summary"),
                    text(payload, "action"),
                    text(payload, "target"),
                    text(payload, "inputSummary")
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private TimelineStepDetail parseTimelineStepDetail(String detail) {
        if (!StringUtils.hasText(detail) || !detail.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(detail, new TypeReference<>() {
            });
            if (!AgentRunTraceEvent.TIMELINE_STEP_SCHEMA.equals(text(payload, "schema", "stepSchema"))) {
                return null;
            }
            return new TimelineStepDetail(
                    text(payload, "category"),
                    text(payload, "action"),
                    text(payload, "target"),
                    text(payload, "source"),
                    text(payload, "riskLevel", "risk_level")
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private String inputSummary(ToolAuditDetail detail, String status) {
        if (detail == null || !"started".equalsIgnoreCase(status)) {
            return null;
        }
        return defaultText(detail.inputSummary(), defaultText(detail.detail(), detail.summary()));
    }

    private String outputSummary(ToolAuditDetail detail, String fallback, String status) {
        if ("failed".equalsIgnoreCase(status) || "started".equalsIgnoreCase(status)) {
            return null;
        }
        if (detail == null) {
            return fallback;
        }
        return defaultText(detail.detail(), detail.summary());
    }

    private String errorSummary(ToolAuditDetail detail, String fallback, String status) {
        if (!"failed".equalsIgnoreCase(status)) {
            return null;
        }
        if (detail == null) {
            return fallback;
        }
        return defaultText(detail.detail(), detail.summary());
    }

    private String text(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String truncateOrNull(String value, int limit) {
        return StringUtils.hasText(value) ? TextUtils.truncate(value, limit) : null;
    }

    private String inferRiskLevel(String category, String target, String detail) {
        String joined = (defaultText(category, "") + " " + defaultText(target, "") + " " + defaultText(detail, ""))
                .toLowerCase(Locale.ROOT);
        if (joined.contains("workspaceedit")
                || joined.contains("runcommand")
                || joined.contains("command")
                || joined.contains("write")
                || joined.contains("delete")
                || joined.contains("move")
                || joined.contains("edit")) {
            return "write";
        }
        if (joined.contains("dangerous") || joined.contains("side_effect")) {
            return "dangerous";
        }
        if ("tool".equalsIgnoreCase(category) || "skill".equalsIgnoreCase(category)) {
            return "read";
        }
        return "";
    }

    private String resolveToolAction(ToolAuditDetail detail) {
        String toolName = defaultText(detail == null ? null : detail.toolName(), "");
        if (detail != null && StringUtils.hasText(detail.action())) {
            return detail.action();
        }
        if (toolName.endsWith(".workspaceRunCommand")) {
            return "command.run";
        }
        if (toolName.endsWith(".workspaceWriteFile")) {
            return "file.write";
        }
        if (toolName.endsWith(".workspaceApplyPatch")) {
            return "file.patch";
        }
        return defaultText(detail == null ? null : detail.eventType(), "tool.invoke");
    }

    private record TimelineStepDetail(String category,
                                      String action,
                                      String target,
                                      String source,
                                      String riskLevel) {
    }

    private record ToolAuditDetail(String eventType,
                                   String toolName,
                                   String toolset,
                                   String status,
                                   String normalizedStatus,
                                   String phase,
                                   String detail,
                                   String summary,
                                   String action,
                                   String target,
                                   String inputSummary) {
    }

    private int nextSequenceNo(String requestId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM agent_run_step WHERE request_id = ?",
                Integer.class,
                requestId
        );
        return (max == null ? 0 : max) + 1;
    }

    /**
     * Resolves the structured run status from canonical lifecycle state. Trace
     * events no longer infer status; if no canonical run exists the projection
     * is {@code UNKNOWN} and diagnostic trace persistence may still continue.
     */
    private String canonicalStatus(RunState state) {
        return state == null ? "UNKNOWN" : state.status().name();
    }

    private LocalDateTime canonicalTime(java.time.Instant value) {
        return value == null
                ? null
                : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private LocalDateTime canonicalStart(RunState state) {
        if (state == null) {
            return null;
        }
        return canonicalTime(
                state.startedAt() == null ? state.acceptedAt() : state.startedAt()
        );
    }

    private Long canonicalDurationMs(RunState state) {
        if (state == null || state.finishedAt() == null) {
            return null;
        }
        java.time.Instant start = state.startedAt() == null
                ? state.acceptedAt()
                : state.startedAt();
        return Duration.between(start, state.finishedAt()).toMillis();
    }

    private RunState canonicalRun(String requestId) {
        if (runStateRepository == null || !StringUtils.hasText(requestId)) {
            return null;
        }
        return runStateRepository.findByRunId(requestId.trim()).orElse(null);
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String serializeQuality(AgentQualityScore quality) {
        if (quality == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(quality);
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 重放一次 turn 的完整 timeline。
     *
     * <p>给定一个 requestId，从三张结构化表（agent_run / agent_run_step /
     * tool_invocation）拼出完整的执行 timeline，供 admin 调试和合规审计使用。
     *
     * <p>区别于 {@link #listTrace(String, String, int)}：那个方法从非结构化的
     * message_event 表读 SYSTEM/TRACE 事件并做用户隔离；这个方法直接读结构化
     * 表，返回 admin 视角下的完整数据（不做用户过滤，由调用端用 @RequireRole(ADMIN)
     * 控制访问）。
     *
     * @return agent_run 主记录 + steps（按 sequence_no 升序）+ toolInvocations
     *         （按 create_time 升序）；agent_run 不存在时返回空 Map。
     */
    public Map<String, Object> replayRun(String requestId) {
        if (jdbcTemplate == null || !StringUtils.hasText(requestId)) {
            return Map.of();
        }
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT request_id, session_key, channel, user_id, product_mode, response_mode, " +
                        "execution_mode, intent, status, started_at, finished_at, duration_ms, " +
                        "total_tokens, quality_score, quality_level, evaluation_json, error_message " +
                        "FROM agent_run WHERE request_id = ? AND deleted = 0",
                requestId
        );
        if (runRows.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> steps = jdbcTemplate.queryForList(
                "SELECT sequence_no, step_name, step_type, status, detail_json, " +
                        "started_at, finished_at, duration_ms, quality_score, quality_level " +
                        "FROM agent_run_step WHERE request_id = ? AND deleted = 0 " +
                        "ORDER BY sequence_no ASC",
                requestId
        );
        List<Map<String, Object>> toolInvocations = jdbcTemplate.queryForList(
                "SELECT id, tool_name, skill_id, toolset, risk_level, status, " +
                        "duration_ms, input_summary, output_summary, error_message, create_time " +
                        "FROM tool_invocation WHERE request_id = ? AND deleted = 0 " +
                        "ORDER BY create_time ASC",
                requestId
        );
        Map<String, Object> result = new LinkedHashMap<>(runRows.get(0));
        result.put("steps", steps);
        result.put("toolInvocations", toolInvocations);
        return result;
    }
}
