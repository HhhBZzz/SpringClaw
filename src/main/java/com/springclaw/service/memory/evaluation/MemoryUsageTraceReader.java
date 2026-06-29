package com.springclaw.service.memory.evaluation;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MemoryUsageTraceReader {

    private static final String PREFIX = "MEMORY_USAGE=";

    private final MessageEventService messageEventService;

    public MemoryUsageTraceReader(MessageEventService messageEventService) {
        this.messageEventService = messageEventService;
    }

    public MemoryUsageTraceView readLatest(String requestId, String userId) {
        if (!StringUtils.hasText(requestId)) {
            return MemoryUsageTraceView.empty("");
        }
        List<MessageEvent> events = messageEventService.listRequestEvents(
                requestId.trim(),
                userId,
                "SYSTEM",
                "OPAR",
                100,
                false
        );
        return events.stream()
                .filter(event -> event != null && StringUtils.hasText(event.getContent()))
                .filter(event -> event.getContent().trim().startsWith(PREFIX))
                .map(this::fromEvent)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseGet(() -> MemoryUsageTraceView.empty(requestId.trim()));
    }

    Optional<MemoryUsageTraceView> parse(String requestId, String sourceEventKey, String content) {
        if (!StringUtils.hasText(content) || !content.trim().startsWith(PREFIX)) {
            return Optional.empty();
        }
        Map<String, String> values = parseAssignments(content.trim().substring(PREFIX.length()));
        return Optional.of(new MemoryUsageTraceView(
                requestId,
                Boolean.parseBoolean(values.getOrDefault("memoryInjected", "false")),
                Boolean.parseBoolean(values.getOrDefault("memoryReferencedInAnswer", "false")),
                values.getOrDefault("kind", "NONE"),
                values.getOrDefault("judgedBy", "unavailable"),
                parseRefs(values.get("refs")),
                sourceEventKey,
                null
        ));
    }

    private Optional<MemoryUsageTraceView> fromEvent(MessageEvent event) {
        return parse(event.getRequestId(), event.getEventKey(), event.getContent())
                .map(view -> new MemoryUsageTraceView(
                        view.requestId(),
                        view.memoryInjected(),
                        view.memoryReferencedInAnswer(),
                        view.memoryReferenceKind(),
                        view.memoryUseJudgedBy(),
                        view.referencedSourceIds(),
                        view.sourceEventKey(),
                        event.getCreateTime()
                ));
    }

    private static Map<String, String> parseAssignments(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : raw.split(",\\s*(?=[A-Za-z][A-Za-z0-9]*=)")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            result.put(part.substring(0, idx).trim(), part.substring(idx + 1).trim());
        }
        return result;
    }

    private static List<String> parseRefs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }
}
