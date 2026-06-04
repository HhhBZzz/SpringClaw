package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityExecutor;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class WebCapabilityExecutor extends AbstractCapabilityExecutor implements CapabilityExecutor {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final WebSearchToolPack webSearchToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;

    public WebCapabilityExecutor(WebSearchToolPack webSearchToolPack,
                                 ScriptSkillToolPack scriptSkillToolPack) {
        this.webSearchToolPack = webSearchToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
    }

    @Override
    public String toolset() {
        return "web";
    }

    @Override
    public boolean supports(AgentDecision decision) {
        return intent(decision, "web_research") && shouldUseGenericWeb(decision);
    }

    @Override
    public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
        ToolExecutionContext context = new ToolExecutionContext(assembled.sessionKey(), assembled.channel(), assembled.userId(), requestId, "AGENT-RUNTIME");
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            if (shouldUseCrawler(assembled.question())) {
                return List.of(run("web.crawler", toolset(), "read", "使用 web_crawler skill 抓取网页", () -> scriptSkillToolPack.executeSkillByGoal("web_crawler", assembled.question())));
            }
            return List.of(run("web.search", toolset(), "read", "联网搜索公开信息", () -> webSearchToolPack.webSearch(assembled.question())));
        }
    }

    private boolean shouldUseCrawler(String question) {
        String text = question == null ? "" : question.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        return URL_PATTERN.matcher(text).find()
                && (lower.contains("web_crawler")
                || lower.contains("web-crawler")
                || lower.contains("抓取")
                || lower.contains("爬取")
                || lower.contains("读取")
                || lower.contains("提取"));
    }

    private boolean shouldUseGenericWeb(AgentDecision decision) {
        if (decision == null || decision.selectedCapabilities() == null || decision.selectedCapabilities().isEmpty()) {
            return true;
        }
        boolean hasGenericWeb = decision.selectedCapabilities().stream()
                .map(this::normalize)
                .anyMatch("web"::equals);
        boolean hasSpecializedRealtime = decision.selectedCapabilities().stream()
                .map(this::normalize)
                .anyMatch(value -> "weather".equals(value) || "news".equals(value) || "exchange".equals(value));
        return hasGenericWeb && !hasSpecializedRealtime;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
