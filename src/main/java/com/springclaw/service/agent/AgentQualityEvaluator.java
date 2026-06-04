package com.springclaw.service.agent;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic evaluator for the live Agent runtime. It grades evidence, not model confidence.
 */
final class AgentQualityEvaluator {

    AgentQualityScore evaluate(AgentDecision decision,
                               CapabilityPlan plan,
                               ReflectionResult reflection,
                               List<CapabilityResult> results,
                               long elapsedMs) {
        List<CapabilityResult> safeResults = results == null ? List.of() : results;
        List<String> reasons = new ArrayList<>();
        int routeScore = routeScore(decision, plan, reasons);
        int toolScore = toolScore(decision, safeResults, reasons);
        int evidenceScore = evidenceScore(decision, reflection, safeResults, reasons);
        int reflectionScore = reflectionScore(reflection, evidenceScore, reasons);
        int answerScore = answerScore(reflection, evidenceScore);
        int costScore = costScore(safeResults, elapsedMs, reasons);
        int riskScore = riskScore(decision, reasons);

        int overall = Math.round(routeScore * 0.15f
                + toolScore * 0.20f
                + evidenceScore * 0.25f
                + reflectionScore * 0.15f
                + answerScore * 0.15f
                + costScore * 0.05f
                + riskScore * 0.05f);
        if (reflection == null || !reflection.sufficient()) {
            overall = Math.min(overall, 59);
        }
        if (safeResults.isEmpty()) {
            overall = Math.min(overall, 35);
        }
        if (expectsWeather(decision) && !hasSuccessfulCapability(safeResults, "weather.")) {
            overall = Math.min(overall, 55);
        }
        if (safeResults.stream().noneMatch(CapabilityResult::successful) && !safeResults.isEmpty()) {
            overall = Math.min(overall, 40);
        }
        if (reasons.isEmpty()) {
            reasons.add("能力、证据与反思均通过");
        }
        String reason = String.join("；", reasons.stream().distinct().limit(4).toList());
        return new AgentQualityScore(
                overall,
                routeScore,
                toolScore,
                evidenceScore,
                reflectionScore,
                answerScore,
                costScore,
                riskScore,
                AgentQualityScore.levelFor(overall),
                reason,
                reasons.stream().distinct().toList()
        );
    }

    private int routeScore(AgentDecision decision, CapabilityPlan plan, List<String> reasons) {
        if (decision == null) {
            reasons.add("缺少 Agent 决策");
            return 20;
        }
        if (decision.isGeneral()) {
            return 90;
        }
        if (decision.selectedCapabilities().isEmpty() && !decision.requiresConfirmation()) {
            reasons.add("非普通请求缺少目标能力");
            return 45;
        }
        if (plan == null || !StringUtils.hasText(plan.executionPath())) {
            reasons.add("能力计划缺失");
            return 55;
        }
        return 92;
    }

    private int toolScore(AgentDecision decision, List<CapabilityResult> results, List<String> reasons) {
        if (decision != null && decision.isGeneral()) {
            return results.isEmpty() ? 95 : 70;
        }
        if (results.isEmpty()) {
            reasons.add("没有执行任何能力");
            return 20;
        }
        long successCount = results.stream().filter(CapabilityResult::successful).count();
        int successScore = Math.round(successCount * 100f / results.size());
        int score = Math.min(100, Math.max(25, successScore));
        if (successCount < results.size()) {
            reasons.add("部分工具执行失败");
            score -= 15;
        }
        if (expectsWeather(decision)) {
            if (hasSuccessfulCapability(results, "weather.")) {
                score = Math.max(score, 92);
            } else {
                reasons.add("weather 工具未成功执行");
                score = Math.min(score, 55);
            }
        }
        return clamp(score);
    }

    private int evidenceScore(AgentDecision decision,
                              ReflectionResult reflection,
                              List<CapabilityResult> results,
                              List<String> reasons) {
        if (results.isEmpty()) {
            reasons.add("缺少证据载荷");
            return 10;
        }
        long usefulPayloads = results.stream()
                .filter(CapabilityResult::successful)
                .filter(result -> StringUtils.hasText(result.payload()))
                .filter(result -> !looksNoisy(result.payload()))
                .count();
        int score = usefulPayloads == 0 ? 30 : Math.min(95, 55 + (int) usefulPayloads * 25);
        if (expectsWeather(decision) && !hasSuccessfulCapability(results, "weather.")) {
            score = Math.min(score, 45);
            reasons.add("天气问题没有实时天气证据");
        }
        if (results.stream().map(CapabilityResult::payload).anyMatch(this::looksNoisy)) {
            reasons.add("证据包含搜索页噪声或截断");
            score = Math.min(score, 60);
        }
        if (reflection != null && reflection.sufficient() && score >= 70) {
            return Math.max(score, 88);
        }
        return clamp(score);
    }

    private int reflectionScore(ReflectionResult reflection, int evidenceScore, List<String> reasons) {
        if (reflection == null) {
            reasons.add("缺少证据反思");
            return 20;
        }
        if (reflection.sufficient() && evidenceScore >= 70) {
            return 90;
        }
        if (reflection.sufficient()) {
            reasons.add("反思通过但证据分偏低");
            return 45;
        }
        if (StringUtils.hasText(reflection.problem())) {
            reasons.add("证据反思未通过");
            return 78;
        }
        return 45;
    }

    private int answerScore(ReflectionResult reflection, int evidenceScore) {
        if (reflection != null && reflection.sufficient() && evidenceScore >= 70) {
            return 86;
        }
        return evidenceScore >= 50 ? 50 : 30;
    }

    private int costScore(List<CapabilityResult> results, long elapsedMs, List<String> reasons) {
        int score = 100;
        if (results.size() > 3) {
            score -= (results.size() - 3) * 8;
        }
        if (elapsedMs > 15_000L) {
            reasons.add("执行耗时偏高");
            score -= 20;
        } else if (elapsedMs > 8_000L) {
            score -= 10;
        }
        return clamp(score);
    }

    private int riskScore(AgentDecision decision, List<String> reasons) {
        if (decision == null) {
            return 60;
        }
        String risk = lower(decision.riskLevel());
        if (decision.requiresConfirmation()) {
            return 95;
        }
        if ("dangerous".equals(risk) || "side_effect".equals(risk) || "write".equals(risk)) {
            reasons.add("风险动作缺少确认");
            return 35;
        }
        return 100;
    }

    private boolean expectsWeather(AgentDecision decision) {
        if (decision == null) {
            return false;
        }
        return "web_research".equalsIgnoreCase(decision.intent())
                && decision.selectedCapabilities().stream().anyMatch(value -> "weather".equalsIgnoreCase(value));
    }

    private boolean hasSuccessfulCapability(List<CapabilityResult> results, String prefix) {
        return results.stream()
                .filter(CapabilityResult::successful)
                .anyMatch(result -> lower(result.capabilityId()).startsWith(prefix));
    }

    private boolean looksNoisy(String payload) {
        String lower = lower(payload);
        return !StringUtils.hasText(payload)
                || lower.contains("<truncated>")
                || lower.contains("被截断")
                || lower.contains("all images news")
                || lower.contains("unexpected end of file");
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
