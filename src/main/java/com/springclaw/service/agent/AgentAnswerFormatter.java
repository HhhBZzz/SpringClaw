package com.springclaw.service.agent;

import com.springclaw.common.util.TextUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Product-facing answer formatter for Agent Runtime responses.
 * Converts internal capability results into natural, user-friendly language.
 *
 * Design principles:
 * 1. Lead with the answer, not the process
 * 2. Surface only the data the user cares about
 * 3. Never expose internal IDs, scores, or execution metrics
 * 4. On failure, be honest and helpful without being verbose
 */
final class AgentAnswerFormatter {

    private static final int PAYLINE_LIMIT = 600;

    String formatRuntimeAnswer(String question,
                               List<CapabilityResult> capabilityResults,
                               VerificationResult verification,
                               String conclusion,
                               String organizerStatus) {
        List<CapabilityResult> results = capabilityResults == null ? List.of() : capabilityResults;
        boolean success = verification != null && verification.sufficient();
        boolean allFailed = !results.isEmpty() && results.stream().noneMatch(CapabilityResult::successful);

        StringBuilder builder = new StringBuilder();

        // --- Main answer ---
        if (success) {
            appendAnswerFromResults(builder, question, results);
        } else if (allFailed) {
            appendFailureAnswer(builder, question, results);
        } else {
            appendPartialAnswer(builder, question, results, verification);
        }

        return builder.toString().trim();
    }

    /**
     * Successful path: extract useful data and present naturally.
     */
    private void appendAnswerFromResults(StringBuilder builder, String question, List<CapabilityResult> results) {
        // Build a natural summary from successful results
        boolean hasContent = false;
        for (CapabilityResult result : results) {
            if (!result.successful() || !StringUtils.hasText(result.payload())) {
                continue;
            }
            String summary = result.summary();
            String payload = result.payload();

            // For price/data-type results, extract the useful lines
            String useful = extractUsefulLines(payload);
            if (StringUtils.hasText(useful)) {
                if (!hasContent) {
                    builder.append("\n\n");
                }
                hasContent = true;
                builder.append(useful).append("\n");
            }
        }
        if (!hasContent && StringUtils.hasText(question)) {
            builder.append("已处理你的请求。");
        }
    }

    /**
     * All-failed path: acknowledge and suggest next steps.
     */
    private void appendFailureAnswer(StringBuilder builder, String question, List<CapabilityResult> results) {
        builder.append("抱歉，这个请求暂时无法完成。");
        // Try to extract a human-readable reason from results
        for (CapabilityResult result : results) {
            String reason = extractFailureReason(result.payload());
            if (StringUtils.hasText(reason)) {
                builder.append("\n").append(reason);
                break;
            }
        }
        builder.append("\n\n你可以换个方式描述你的需求，我再来帮你。");
    }

    /**
     * Insufficient/partial path: show what we have, acknowledge what's missing.
     */
    private void appendPartialAnswer(StringBuilder builder, String question,
                                     List<CapabilityResult> results,
                                     VerificationResult verification) {
        // Show any useful data we did get
        boolean hasContent = false;
        for (CapabilityResult result : results) {
            if (!result.successful() || !StringUtils.hasText(result.payload())) {
                continue;
            }
            String useful = extractUsefulLines(result.payload());
            if (StringUtils.hasText(useful)) {
                if (!hasContent) {
                    builder.append("\n");
                }
                hasContent = true;
                builder.append(useful).append("\n");
            }
        }
        if (!hasContent) {
            builder.append("目前获取的信息还不够完整。");
            builder.append("\n\n");
            builder.append("你可以：\n");
            builder.append("- 换一种方式提问，比如直接告诉我币种名称（如 BTC、ETH）\n");
            builder.append("- 稍后再试一次\n");
        }
    }

    /**
     * Extract the useful data lines from a capability payload,
     * stripping internal metadata (skill=, exitCode=, dryRun=, symbols=, unsupported=).
     */
    private String extractUsefulLines(String payload) {
        if (!StringUtils.hasText(payload)) {
            return "";
        }
        String truncated = TextUtils.truncate(payload, PAYLINE_LIMIT);
        StringBuilder out = new StringBuilder();
        for (String line : truncated.split("\n")) {
            String trimmed = line.trim();
            // Skip internal metadata lines
            if (isInternalMetadata(trimmed)) {
                continue;
            }
            // Skip raw execution trace lines
            if (trimmed.startsWith("skill=") || trimmed.startsWith("exitCode=")
                    || trimmed.startsWith("dryRun=") || trimmed.startsWith("symbols=")
                    || trimmed.startsWith("unsupported=") || trimmed.startsWith("status=")
                    || trimmed.startsWith("error=")) {
                continue;
            }
            if (StringUtils.hasText(trimmed)) {
                // Convert "- BTC: usd=61280.4, cny=416389.78, 24h=-3.99%" → natural format
                String formatted = formatDataLine(trimmed);
                out.append(formatted).append("\n");
            }
        }
        return out.toString().trim();
    }

    private boolean isInternalMetadata(String line) {
        return line.startsWith("LOCAL_WORKSPACE") || line.startsWith("[REFLECT]")
                || line.startsWith("ROUTING=") || line.startsWith("PLAN=")
                || line.startsWith("ACT=") || line.startsWith("VERIFICATION:");
    }

    /**
     * Convert raw data lines into more readable format.
     */
    private String formatDataLine(String line) {
        String result = line;
        // Format crypto price lines: "- BTC: usd=61280.4, cny=416389.78, 24h=-3.99%"
        if (result.matches("^- \\w+: usd=.*")) {
            result = result.replace("usd=", "USD ").replace(", cny=", " / CNY ")
                    .replace(", 24h=", " (24h ").replace("%", "%)");
        }
        // Format weather-like lines
        if (result.contains("城市:") && result.contains("天气:")) {
            // Already readable, pass through
        }
        return result;
    }

    /**
     * Extract a human-readable failure reason from payload.
     */
    private String extractFailureReason(String payload) {
        if (!StringUtils.hasText(payload)) {
            return "";
        }
        if (payload.contains("no supported symbols")) {
            return "我没能识别出你要查询的具体品种，请直接告诉我名称，比如 BTC 或 比特币。";
        }
        if (payload.contains("unreachable") || payload.contains("timed out") || payload.contains("SSL")) {
            return "当前无法连接到数据服务，请稍后再试。";
        }
        if (payload.contains("failed") && payload.contains("error=")) {
            String error = extractField(payload, "error=");
            if (StringUtils.hasText(error)) {
                return "出错原因：" + error;
            }
        }
        return "";
    }

    private String extractField(String text, String key) {
        int idx = text.indexOf(key);
        if (idx < 0) return "";
        String rest = text.substring(idx + key.length());
        int end = rest.indexOf("\n");
        return end > 0 ? rest.substring(0, end).trim() : rest.trim();
    }

}