package com.springclaw.service.agent.executor;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityExecutor;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RealtimeCapabilityExecutor extends AbstractCapabilityExecutor implements CapabilityExecutor {

    private static final int MAX_PAYLOAD_CHARS = 5000;
    private static final Pattern CURRENCY_CODE_PATTERN = Pattern.compile("\\b([A-Za-z]{3})\\b");

    private final WeatherToolPack weatherToolPack;
    private final NewsToolPack newsToolPack;
    private final ExchangeRateToolPack exchangeRateToolPack;

    public RealtimeCapabilityExecutor(WeatherToolPack weatherToolPack,
                                      NewsToolPack newsToolPack,
                                      ExchangeRateToolPack exchangeRateToolPack) {
        this.weatherToolPack = weatherToolPack;
        this.newsToolPack = newsToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
    }

    @Override
    public String toolset() {
        return "realtime";
    }

    @Override
    public boolean supports(AgentDecision decision) {
        return intent(decision, "web_research") && selectedAny(decision, "weather", "news", "exchange");
    }

    @Override
    public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
        ToolExecutionContext context = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "AGENT-RUNTIME",
                requestId,
                null
        );
        List<CapabilityResult> results = new ArrayList<>();
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            if (selected(decision, "weather")) {
                String city = extractWeatherCity(assembled.question());
                results.add(runRealtime("weather.current", "weather", "查询实时天气", () -> weatherToolPack.queryWeather(city)));
            }
            if (selected(decision, "news")) {
                String keyword = extractNewsKeyword(assembled.question());
                results.add(runRealtime("news.search", "news", "检索新闻摘要", () -> newsToolPack.searchNews(keyword)));
            }
            if (selected(decision, "exchange")) {
                CurrencyPair pair = extractCurrencyPair(assembled.question());
                results.add(runRealtime("exchange.rate", "exchange", "查询汇率", () -> exchangeRateToolPack.queryExchangeRate(pair.base(), pair.target())));
            }
        }
        return List.copyOf(results);
    }

    private CapabilityResult runRealtime(String capabilityId,
                                         String toolset,
                                         String summary,
                                         Callable<String> action) {
        long startedAt = System.currentTimeMillis();
        try {
            String payload = TextUtils.truncate(action.call(), MAX_PAYLOAD_CHARS);
            String status = looksLikeFailure(payload) ? "failed" : "success";
            return new CapabilityResult(capabilityId, toolset, status, summary, payload, System.currentTimeMillis() - startedAt, "read");
        } catch (Exception ex) {
            return new CapabilityResult(capabilityId, toolset, "failed", summary, ex.getClass().getSimpleName() + ": " + ex.getMessage(), System.currentTimeMillis() - startedAt, "read");
        }
    }

    private boolean selectedAny(AgentDecision decision, String... capabilityIds) {
        for (String capabilityId : capabilityIds) {
            if (selected(decision, capabilityId)) {
                return true;
            }
        }
        return false;
    }

    private boolean selected(AgentDecision decision, String capabilityId) {
        if (decision == null || decision.selectedCapabilities() == null) {
            return false;
        }
        String expected = TextUtils.normalize(capabilityId);
        return decision.selectedCapabilities().stream()
                .map(TextUtils::normalize)
                .anyMatch(expected::equals);
    }

    private String extractWeatherCity(String question) {
        String text = TextUtils.safe(question).trim();
        String cleaned = text
                .replaceAll("(?i)weather", "")
                .replace("天气怎么样", "")
                .replace("天气怎样", "")
                .replace("天气如何", "")
                .replace("天气", "")
                .replace("气温", "")
                .replace("温度", "")
                .replace("实时", "")
                .replace("今天", "")
                .replace("现在", "")
                .replace("查询", "")
                .replace("请问", "")
                .replace("帮我", "")
                .replace("一下", "")
                .replaceAll("[，。！？、,.!?\\s]", "")
                .trim();
        return StringUtils.hasText(cleaned) ? cleaned : text;
    }

    private String extractNewsKeyword(String question) {
        String text = TextUtils.safe(question).trim();
        String cleaned = text
                .replace("新闻", "")
                .replace("最新", "")
                .replace("检索", "")
                .replace("搜索", "")
                .replace("查询", "")
                .replace("看看", "")
                .trim();
        return StringUtils.hasText(cleaned) ? cleaned : text;
    }

    private CurrencyPair extractCurrencyPair(String question) {
        String text = TextUtils.safe(question);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("美元") && (lower.contains("人民币") || lower.contains("cny"))) {
            return new CurrencyPair("USD", "CNY");
        }
        if (lower.contains("人民币") && (lower.contains("美元") || lower.contains("usd"))) {
            return new CurrencyPair("CNY", "USD");
        }
        List<String> codes = new ArrayList<>();
        Matcher matcher = CURRENCY_CODE_PATTERN.matcher(text);
        while (matcher.find()) {
            codes.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        if (codes.size() >= 2) {
            return new CurrencyPair(codes.get(0), codes.get(1));
        }
        if (codes.size() == 1) {
            return new CurrencyPair(codes.get(0), "CNY");
        }
        return new CurrencyPair("USD", "CNY");
    }

    private boolean looksLikeFailure(String payload) {
        String lower = TextUtils.safe(payload).toLowerCase(Locale.ROOT);
        return !StringUtils.hasText(lower)
                || lower.contains("查询失败")
                || lower.contains("检索失败")
                || lower.contains("未返回有效")
                || lower.contains("服务返回为空")
                || lower.contains("工具未开启")
                || lower.contains("请输入")
                || lower.contains("不能为空")
                || lower.contains("temporarily unavailable");
    }

    private record CurrencyPair(String base, String target) {
    }
}
