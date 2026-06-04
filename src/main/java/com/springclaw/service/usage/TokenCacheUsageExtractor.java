package com.springclaw.service.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts provider-specific prompt-cache counters from Spring AI usage metadata.
 */
public final class TokenCacheUsageExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_DEPTH = 6;
    private static final int MAX_RAW_JSON_CHARS = 6000;

    private TokenCacheUsageExtractor() {
    }

    public static TokenCacheUsage extract(Integer promptTokens, Object... sources) {
        Accumulator accumulator = new Accumulator();
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (sources != null) {
            for (Object source : sources) {
                inspect(source, accumulator, visited, 0);
            }
        }
        Integer hitTokens = firstNonNull(accumulator.promptCacheHitTokens, accumulator.cacheReadInputTokens, accumulator.cachedTokens);
        Integer missTokens = firstNonNull(accumulator.promptCacheMissTokens, accumulator.cacheCreationInputTokens);
        if (missTokens == null && promptTokens != null && hitTokens != null) {
            missTokens = Math.max(0, promptTokens - hitTokens);
        }
        return new TokenCacheUsage(normalize(hitTokens), normalize(missTokens), rawUsageJson(sources));
    }

    private static void inspect(Object source, Accumulator accumulator, Set<Object> visited, int depth) {
        if (source == null || depth > MAX_DEPTH || isScalar(source)) {
            return;
        }
        if (!visited.add(source)) {
            return;
        }
        if (source instanceof Usage usage) {
            inspect(usage.getNativeUsage(), accumulator, visited, depth + 1);
        }
        if (source instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeKey(String.valueOf(entry.getKey()));
                Object value = entry.getValue();
                Integer number = asInteger(value);
                if (number != null) {
                    capture(key, number, accumulator);
                }
                inspect(value, accumulator, visited, depth + 1);
            }
            return;
        }
        if (source instanceof Collection<?> collection) {
            for (Object item : collection) {
                inspect(item, accumulator, visited, depth + 1);
            }
            return;
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            for (int i = 0; i < length; i++) {
                inspect(Array.get(source, i), accumulator, visited, depth + 1);
            }
            return;
        }
        inspectBeanGetters(source, accumulator, visited, depth);
    }

    private static void inspectBeanGetters(Object source, Accumulator accumulator, Set<Object> visited, int depth) {
        Package pkg = source.getClass().getPackage();
        String packageName = pkg == null ? "" : pkg.getName();
        if (packageName.startsWith("java.") || packageName.startsWith("javax.") || packageName.startsWith("jakarta.")) {
            return;
        }
        for (Method method : source.getClass().getMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType() == Void.TYPE
                    || "getClass".equals(method.getName())) {
                continue;
            }
            String property = propertyName(method.getName());
            if (!StringUtils.hasText(property)) {
                continue;
            }
            try {
                Object value = method.invoke(source);
                Integer number = asInteger(value);
                if (number != null) {
                    capture(normalizeKey(property), number, accumulator);
                }
                inspect(value, accumulator, visited, depth + 1);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Provider metadata objects are best-effort; failed getters must not break chat.
            }
        }
    }

    private static void capture(String key, Integer value, Accumulator accumulator) {
        switch (key) {
            case "promptcachehittokens", "promptcachehitinputtokens" -> accumulator.promptCacheHitTokens = value;
            case "promptcachemisstokens", "promptcachemissinputtokens" -> accumulator.promptCacheMissTokens = value;
            case "cachedtokens" -> accumulator.cachedTokens = value;
            case "cachereadinputtokens" -> accumulator.cacheReadInputTokens = value;
            case "cachecreationinputtokens" -> accumulator.cacheCreationInputTokens = value;
            default -> {
            }
        }
    }

    private static String rawUsageJson(Object... sources) {
        if (sources == null || sources.length == 0) {
            return "";
        }
        try {
            List<Object> renderedSources = java.util.Arrays.stream(sources)
                    .filter(Objects::nonNull)
                    .map(TokenCacheUsageExtractor::renderableSource)
                    .toList();
            if (renderedSources.isEmpty()) {
                return "";
            }
            Object raw = renderedSources.size() == 1 ? renderedSources.get(0) : renderedSources;
            String json = OBJECT_MAPPER.writeValueAsString(raw);
            if (json.length() > MAX_RAW_JSON_CHARS) {
                return json.substring(0, MAX_RAW_JSON_CHARS);
            }
            return json;
        } catch (JsonProcessingException | RuntimeException ex) {
            return "";
        }
    }

    private static Object renderableSource(Object source) {
        if (source instanceof Usage usage) {
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage != null) {
                return nativeUsage;
            }
            Map<String, Object> usageMap = new LinkedHashMap<>();
            usageMap.put("promptTokens", usage.getPromptTokens());
            usageMap.put("completionTokens", usage.getCompletionTokens());
            usageMap.put("totalTokens", usage.getTotalTokens());
            return usageMap;
        }
        return source;
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return normalize(number.intValue());
        }
        if (value instanceof CharSequence text) {
            try {
                return normalize(Integer.parseInt(text.toString().trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer normalize(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private static String propertyName(String methodName) {
        if (methodName == null) {
            return "";
        }
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return methodName.substring(2, 3).toLowerCase() + methodName.substring(3);
        }
        return "";
    }

    private static Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static final class Accumulator {
        private Integer promptCacheHitTokens;
        private Integer promptCacheMissTokens;
        private Integer cachedTokens;
        private Integer cacheReadInputTokens;
        private Integer cacheCreationInputTokens;
    }
}
