package com.springclaw.service.agent;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Deterministic final-answer contract for Agent Runtime responses.
 */
final class AgentAnswerFormatter {

    private static final int RESULT_PAYLOAD_LIMIT = 900;

    String formatRuntimeAnswer(String question,
                               List<CapabilityResult> capabilityResults,
                               VerificationResult verification,
                               String conclusion,
                               String organizerStatus) {
        List<CapabilityResult> results = capabilityResults == null ? List.of() : capabilityResults;
        StringBuilder builder = new StringBuilder();
        builder.append("结论：\n")
                .append(normalizeConclusion(conclusion, verification))
                .append("\n\n");

        builder.append("依据：\n");
        if (verification != null && StringUtils.hasText(verification.summary())) {
            builder.append("- 校验：").append(verification.summary()).append("\n");
        }
        if (results.isEmpty()) {
            builder.append("- 本轮没有取得可用能力结果。");
            if (StringUtils.hasText(question)) {
                builder.append("用户请求：").append(question.trim());
            }
            builder.append("\n");
        } else {
            for (CapabilityResult result : results) {
                builder.append("- ")
                        .append(safe(result.capabilityId(), "unknown"))
                        .append(" [")
                        .append(safe(result.status(), "unknown"))
                        .append("] ")
                        .append(safe(result.summary(), "已执行能力"))
                        .append("\n  结果：")
                        .append(renderPayload(result.payload()))
                        .append("\n");
            }
        }

        builder.append("\n执行状态：\n")
                .append("- 能力执行：")
                .append(results.size())
                .append(" 个，成功 ")
                .append(results.stream().filter(CapabilityResult::successful).count())
                .append(" 个。\n");
        if (verification != null && verification.quality() != null) {
            AgentQualityScore quality = verification.quality();
            builder.append("- 质量评分：")
                    .append(quality.overallScore())
                    .append("/100（")
                    .append(quality.level())
                    .append("）");
            if (StringUtils.hasText(quality.reason())) {
                builder.append("，").append(quality.reason());
            }
            builder.append("\n");
        }
        if (StringUtils.hasText(organizerStatus)) {
            builder.append("- 回答整理：").append(organizerStatus.trim()).append("\n");
        }

        builder.append("\n下一步：\n")
                .append(nextStep(verification));
        return builder.toString().trim();
    }

    private String normalizeConclusion(String conclusion, VerificationResult verification) {
        if (StringUtils.hasText(conclusion)) {
            return stripRepeatedContractHeading(conclusion.trim());
        }
        if (verification != null && !verification.sufficient()) {
            return "当前证据不足以可靠回答这次请求。";
        }
        return "已完成这次请求，并基于已获取的证据整理结果。";
    }

    private String stripRepeatedContractHeading(String text) {
        String value = text;
        if (value.startsWith("结论：")) {
            value = value.substring("结论：".length()).trim();
        }
        return StringUtils.hasText(value) ? value : "已完成这次请求，并基于已获取的证据整理结果。";
    }

    private String renderPayload(String payload) {
        String value = truncate(payload, RESULT_PAYLOAD_LIMIT);
        if (!StringUtils.hasText(value)) {
            return "（无详情）";
        }
        return value.replace("\r\n", "\n").replace("\n", "\n  ");
    }

    private String nextStep(VerificationResult verification) {
        if (verification != null && !verification.sufficient()) {
            return "需要补齐缺失能力结果后再继续；当前回答只保留已验证的证据，不扩写未证实内容。";
        }
        return "如果需要，我可以继续展开某个证据、追加工具执行，或把结果转成更适合交付的报告格式。";
    }

    private String truncate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...<TRUNCATED>";
    }

    private String safe(String text, String fallback) {
        return StringUtils.hasText(text) ? text.trim() : fallback;
    }
}
