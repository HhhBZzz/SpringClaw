package com.springclaw.service.memory.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class StrictJsonParser {

    private final ObjectMapper objectMapper;

    StrictJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    <T> T parseObject(String raw, Class<T> type) throws Exception {
        return objectMapper.readValue(extractObject(raw), type);
    }

    private static String extractObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("model response is blank");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int fenceEnd = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && fenceEnd > firstLineEnd) {
                text = text.substring(firstLineEnd + 1, fenceEnd).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model response does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
