package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 在路由前消解“北京呢”这类短追问，让能力选择继承上一轮业务意图。
 */
@Component
public class ContextualFollowUpQuestionResolver {

    private static final int RECENT_EVENT_LIMIT = 8;
    private static final Pattern TARGET_PATTERN = Pattern.compile("^[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z·.\\-]{0,23}$");

    private final MessageEventService messageEventService;

    public ContextualFollowUpQuestionResolver(MessageEventService messageEventService) {
        this.messageEventService = messageEventService;
    }

    public String resolve(String sessionKey, String question) {
        String original = normalize(question);
        if (!StringUtils.hasText(original) || hasExplicitWeatherIntent(original)) {
            return original;
        }
        FollowUpTarget target = extractFollowUpTarget(original);
        if (target == null || !StringUtils.hasText(sessionKey)) {
            return original;
        }

        List<MessageEvent> recentEvents = recentEvents(sessionKey);
        WeatherContext weatherContext = inferWeatherContext(recentEvents);
        if (weatherContext == null) {
            return original;
        }

        if (target.temporalOnly()) {
            String previousTarget = inferPreviousWeatherTarget(recentEvents);
            return StringUtils.hasText(previousTarget)
                    ? previousTarget + target.text() + "天气"
                    : original;
        }
        return target.text() + weatherContext.suffix();
    }

    private List<MessageEvent> recentEvents(String sessionKey) {
        try {
            return messageEventService.listRecent(sessionKey, RECENT_EVENT_LIMIT);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private WeatherContext inferWeatherContext(List<MessageEvent> events) {
        String joined = joinRecentText(events).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(joined) || !hasWeatherEvidence(joined)) {
            return null;
        }
        if (containsAny(joined, "明天", "tomorrow")) {
            return new WeatherContext("明天天气");
        }
        if (containsAny(joined, "今天", "今日")) {
            return new WeatherContext("今天天气");
        }
        if (containsAny(joined, "现在", "当前", "实时", "此刻", "weather.current", "温度", "气温")) {
            return new WeatherContext("现在温度");
        }
        return new WeatherContext("天气");
    }

    private boolean hasWeatherEvidence(String text) {
        return containsAny(text,
                "weather.current",
                "当前天气",
                "天气",
                "气温",
                "温度",
                "下雨",
                "降水",
                "湿度",
                "open-meteo",
                "forecast");
    }

    private String inferPreviousWeatherTarget(List<MessageEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            MessageEvent event = events.get(i);
            if (event == null || !"USER".equalsIgnoreCase(safe(event.getRole()))) {
                continue;
            }
            String text = normalizedEventText(event);
            if (!hasExplicitWeatherIntent(text)) {
                continue;
            }
            String candidate = text
                    .replaceAll("(现在|当前|实时|此刻|今天|今日|明天|后天|天气|气温|温度|下雨|降水|湿度|怎样|怎么样|如何|多少|吗|呢)", "")
                    .replaceAll("[\\s。！？?，,、：:；;]", "")
                    .trim();
            if (isTargetText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private FollowUpTarget extractFollowUpTarget(String question) {
        String cleaned = question
                .replaceAll("[\\s。！？?，,、：:；;]", "")
                .replaceFirst("^(那|那么|还有|另外|顺便|再查|也查|换成|看下|看看|查一下|查询|帮我查)", "")
                .replaceAll("(呢|吗)$", "")
                .trim();
        if (!StringUtils.hasText(cleaned) || looksLikeNonTargetFollowUp(cleaned)) {
            return null;
        }
        if (isTemporalFollowUp(cleaned)) {
            return new FollowUpTarget(cleaned, true);
        }
        if (!isTargetText(cleaned)) {
            return null;
        }
        return new FollowUpTarget(cleaned, false);
    }

    private boolean isTargetText(String text) {
        return StringUtils.hasText(text)
                && text.length() <= 24
                && TARGET_PATTERN.matcher(text).matches()
                && !hasExplicitWeatherIntent(text)
                && !looksLikeNonTargetFollowUp(text);
    }

    private boolean isTemporalFollowUp(String text) {
        return "今天".equals(text) || "明天".equals(text) || "后天".equals(text);
    }

    private boolean looksLikeNonTargetFollowUp(String text) {
        String lower = safe(text).toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "为什么",
                "怎么",
                "如何",
                "原因",
                "依据",
                "来源",
                "详细",
                "展开",
                "继续",
                "总结",
                "报告",
                "谢谢",
                "不用",
                "可以",
                "好的",
                "好");
    }

    private boolean hasExplicitWeatherIntent(String text) {
        String lower = safe(text).toLowerCase(Locale.ROOT);
        return containsAny(lower, "天气", "气温", "温度", "下雨", "降水", "湿度", "weather", "forecast");
    }

    private String joinRecentText(List<MessageEvent> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (MessageEvent event : events) {
            String text = normalizedEventText(event);
            if (StringUtils.hasText(text)) {
                builder.append(text).append(' ');
            }
        }
        return builder.toString().trim();
    }

    private String normalizedEventText(MessageEvent event) {
        if (event == null) {
            return "";
        }
        String role = safe(event.getRole());
        String eventType = safe(event.getEventType()).toUpperCase(Locale.ROOT);
        if ("USER".equalsIgnoreCase(role) && "CHAT".equals(eventType)) {
            return ConversationEventTextSupport.extractUserQuestion(event.getContent());
        }
        if ("ASSISTANT".equalsIgnoreCase(role) && "CHAT".equals(eventType)) {
            return ConversationEventTextSupport.extractAssistantAnswer(event.getContent());
        }
        return normalize(event.getContent());
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return ConversationEventTextSupport.normalize(text);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record FollowUpTarget(String text, boolean temporalOnly) {
    }

    private record WeatherContext(String suffix) {
    }
}
