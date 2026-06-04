package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunTraceService.class);

    private final MessageEventService messageEventService;
    private final ObjectMapper objectMapper;

    public AgentRunTraceService(MessageEventService messageEventService, ObjectMapper objectMapper) {
        this.messageEventService = messageEventService;
        this.objectMapper = objectMapper;
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

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
