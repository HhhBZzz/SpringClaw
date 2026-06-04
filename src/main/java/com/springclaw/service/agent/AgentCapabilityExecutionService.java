package com.springclaw.service.agent;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.pack.SystemHealthToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes safe read-only capabilities before model summarization.
 */
@Service
public class AgentCapabilityExecutionService {

    private static final int MAX_PAYLOAD_CHARS = 5000;
    private static final Pattern CURRENCY_CODE_PATTERN = Pattern.compile("\\b([A-Za-z]{3})\\b");
    private static final Set<String> PROVIDER_CAPABILITIES = Set.of(
            "system", "workspace-search", "workspace-review", "file", "local-files",
            "web", "weather", "news", "exchange", "script-skill", "skill-library",
            "scheduled-task", "dangerous-action"
    );

    private final AiProviderService aiProviderService;
    private final WorkspaceReviewToolPack workspaceReviewToolPack;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final LocalFilesystemToolPack localFilesystemToolPack;
    private final WebSearchToolPack webSearchToolPack;
    private final WeatherToolPack weatherToolPack;
    private final NewsToolPack newsToolPack;
    private final ExchangeRateToolPack exchangeRateToolPack;
    private final SkillLibraryToolPack skillLibraryToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;
    private final SystemToolPack systemToolPack;
    private final SystemHealthToolPack systemHealthToolPack;
    private final boolean enabled;

    private AgentCapabilityExecutionService() {
        this.aiProviderService = null;
        this.workspaceReviewToolPack = null;
        this.workspaceSearchToolPack = null;
        this.localFilesystemToolPack = null;
        this.webSearchToolPack = null;
        this.weatherToolPack = null;
        this.newsToolPack = null;
        this.exchangeRateToolPack = null;
        this.skillLibraryToolPack = null;
        this.scriptSkillToolPack = null;
        this.systemToolPack = null;
        this.systemHealthToolPack = null;
        this.enabled = false;
    }

    @Autowired
    public AgentCapabilityExecutionService(AiProviderService aiProviderService,
                                           WorkspaceReviewToolPack workspaceReviewToolPack,
                                           WorkspaceSearchToolPack workspaceSearchToolPack,
                                           LocalFilesystemToolPack localFilesystemToolPack,
                                           WebSearchToolPack webSearchToolPack,
                                           WeatherToolPack weatherToolPack,
                                           NewsToolPack newsToolPack,
                                           ExchangeRateToolPack exchangeRateToolPack,
                                           SkillLibraryToolPack skillLibraryToolPack,
                                           ScriptSkillToolPack scriptSkillToolPack,
                                           SystemToolPack systemToolPack,
                                           SystemHealthToolPack systemHealthToolPack) {
        this.aiProviderService = aiProviderService;
        this.workspaceReviewToolPack = workspaceReviewToolPack;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.localFilesystemToolPack = localFilesystemToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.newsToolPack = newsToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.skillLibraryToolPack = skillLibraryToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.systemToolPack = systemToolPack;
        this.systemHealthToolPack = systemHealthToolPack;
        this.enabled = true;
    }

    public static AgentCapabilityExecutionService noop() {
        return new AgentCapabilityExecutionService();
    }

    public List<AgentCapabilityResult> execute(AgentDecision decision,
                                               AssembledContext assembled,
                                               String requestId) {
        if (!enabled || decision == null || assembled == null || decision.isGeneral()
                || decision.requiresConfirmation() || decision.isDangerous()) {
            return List.of();
        }

        ToolExecutionContext context = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "ACT-DIRECT"
        );
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            return switch (decision.intent()) {
                case "workspace_analysis" -> executeWorkspace(assembled);
                case "local_files" -> executeLocalFiles(assembled);
                case "web_research" -> executeWeb(decision, assembled);
                case "skill_task" -> executeSkill(decision, assembled);
                case "model_control" -> executeModelControl();
                default -> List.of();
            };
        }
    }

    private List<AgentCapabilityResult> executeWorkspace(AssembledContext assembled) {
        List<AgentCapabilityResult> results = new ArrayList<>();
        results.add(run("workspace-review", "审查当前工作区", () -> workspaceReviewToolPack.reviewWorkspace(assembled.question())));
        results.add(run("workspace-search", "分析相关源码位置", () -> workspaceSearchToolPack.analyzeWorkspaceTask(assembled.question())));
        return results;
    }

    private List<AgentCapabilityResult> executeLocalFiles(AssembledContext assembled) {
        List<AgentCapabilityResult> results = new ArrayList<>();
        results.add(run("local-files", "列出授权本地目录", localFilesystemToolPack::listAuthorizedRoots));
        String keyword = extractSearchKeyword(assembled.question());
        if (StringUtils.hasText(keyword)) {
            results.add(run("local-files.search", "搜索授权目录文件", () -> localFilesystemToolPack.searchAuthorizedFiles(keyword)));
        }
        return results;
    }

    private List<AgentCapabilityResult> executeWeb(AgentDecision decision, AssembledContext assembled) {
        List<AgentCapabilityResult> results = new ArrayList<>();
        if (selected(decision, "weather")) {
            String city = extractWeatherCity(assembled.question());
            results.add(runRealtime("weather.current", "查询实时天气", () -> weatherToolPack.queryWeather(city)));
        }
        if (selected(decision, "news")) {
            String keyword = extractNewsKeyword(assembled.question());
            results.add(runRealtime("news.search", "检索新闻摘要", () -> newsToolPack.searchNews(keyword)));
        }
        if (selected(decision, "exchange")) {
            CurrencyPair pair = extractCurrencyPair(assembled.question());
            results.add(runRealtime("exchange.rate", "查询汇率", () -> exchangeRateToolPack.queryExchangeRate(pair.base(), pair.target())));
        }
        if (!results.isEmpty()) {
            return List.copyOf(results);
        }
        return List.of(run("web", "联网搜索公开信息", () -> webSearchToolPack.webSearch(assembled.question())));
    }

    private List<AgentCapabilityResult> executeSkill(AgentDecision decision, AssembledContext assembled) {
        String skillId = decision.selectedCapabilities().stream()
                .map(this::normalize)
                .filter(value -> StringUtils.hasText(value))
                .filter(value -> !PROVIDER_CAPABILITIES.contains(value))
                .findFirst()
                .orElse("");
        if (StringUtils.hasText(skillId)) {
            return List.of(run("script-skill." + skillId, "执行匹配 skill", () -> scriptSkillToolPack.executeSkillByGoal(skillId, assembled.question())));
        }
        return List.of(run("skill-library", "列出可用 skill", skillLibraryToolPack::skillsList));
    }

    private List<AgentCapabilityResult> executeModelControl() {
        List<AgentCapabilityResult> results = new ArrayList<>();
        results.add(run("system-health", "检查 Spring Boot 运行健康状态", systemHealthToolPack::runtimeHealth));
        results.add(run("model-status", "读取当前模型状态", this::modelStatus));
        results.add(run("system.jvm", "读取 JVM 状态", systemToolPack::jvmInfo));
        results.add(run("system.time", "读取系统时间", systemToolPack::now));
        return results;
    }

    private AgentCapabilityResult run(String capabilityId, String summary, Callable<String> action) {
        try {
            String payload = action.call();
            return new AgentCapabilityResult(capabilityId, "success", summary, trim(payload));
        } catch (Exception ex) {
            return new AgentCapabilityResult(capabilityId, "failed", summary, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private AgentCapabilityResult runRealtime(String capabilityId, String summary, Callable<String> action) {
        try {
            String payload = trim(action.call());
            String status = looksLikeFailure(payload) ? "failed" : "success";
            return new AgentCapabilityResult(capabilityId, status, summary, payload);
        } catch (Exception ex) {
            return new AgentCapabilityResult(capabilityId, "failed", summary, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private String modelStatus() {
        AiProviderService.ActiveChatClient client = aiProviderService.activeClient();
        return "provider=" + client.providerId()
                + "\nmodel=" + client.model()
                + "\navailable=" + client.available()
                + "\nbaseUrl=" + client.baseUrl()
                + "\nunavailableReason=" + client.unavailableReason();
    }

    private String extractSearchKeyword(String question) {
        String text = question == null ? "" : question.trim();
        if (!StringUtils.hasText(text)) {
            return "";
        }
        for (String keyword : List.of("桌面", "下载", "简历", "论文", "docx", "pdf", "md", "csv", "json")) {
            if (text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                return keyword;
            }
        }
        return text.length() <= 40 ? text : "";
    }

    private boolean selected(AgentDecision decision, String capabilityId) {
        if (decision == null || decision.selectedCapabilities() == null) {
            return false;
        }
        String expected = normalize(capabilityId);
        return decision.selectedCapabilities().stream()
                .map(this::normalize)
                .anyMatch(expected::equals);
    }

    private String extractWeatherCity(String question) {
        String text = question == null ? "" : question.trim();
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
        String text = question == null ? "" : question.trim();
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
        String text = question == null ? "" : question;
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
        String lower = payload == null ? "" : payload.toLowerCase(Locale.ROOT);
        return !StringUtils.hasText(lower)
                || lower.contains("查询失败")
                || lower.contains("检索失败")
                || lower.contains("未返回有效")
                || lower.contains("服务返回为空")
                || lower.contains("工具未开启")
                || lower.contains("请输入")
                || lower.contains("不能为空")
                || lower.contains("未找到币种")
                || lower.contains("暂不可用");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String trim(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= MAX_PAYLOAD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PAYLOAD_CHARS) + "\n...<TRUNCATED>";
    }

    private record CurrencyPair(String base, String target) {
    }
}
