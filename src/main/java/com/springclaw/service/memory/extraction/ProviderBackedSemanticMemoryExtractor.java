package com.springclaw.service.memory.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ProviderBackedSemanticMemoryExtractor implements SemanticMemoryExtractor {

    private final ProviderMemoryModelClient modelClient;
    private final StrictJsonParser jsonParser;
    private final String extractorProviderId;
    private final String fallbackProviderId;

    ProviderBackedSemanticMemoryExtractor(
            ProviderMemoryModelClient modelClient,
            StrictJsonParser jsonParser,
            @Value("${springclaw.memory.semantic-extraction.extractor-provider-id:coding-plan}")
            String extractorProviderId,
            @Value("${springclaw.memory.semantic-extraction.fallback-provider-id:deepseek}")
            String fallbackProviderId
    ) {
        this.modelClient = modelClient;
        this.jsonParser = jsonParser;
        this.extractorProviderId = extractorProviderId;
        this.fallbackProviderId = fallbackProviderId;
    }

    @Override
    public SemanticMemoryExtractionResult extract(TerminalMemoryExtractionContext context) throws Exception {
        String first = call(context, renderPrompt(context));
        try {
            return jsonParser.parseObject(first, SemanticMemoryExtractionResult.class);
        } catch (Exception parseFailure) {
            String repaired = call(context, """
                    上一次输出不是合法 JSON。请只输出符合 springclaw.semantic-memory-extraction.v1 的 JSON 对象，
                    不要 Markdown，不要解释。源证据不变：
                    """ + renderEvidence(context));
            return jsonParser.parseObject(repaired, SemanticMemoryExtractionResult.class);
        }
    }

    private String call(TerminalMemoryExtractionContext context, String userPrompt) throws Exception {
        return modelClient.call(
                extractorProviderId,
                fallbackProviderId,
                "memory-semantic-extraction",
                context,
                "你是记忆抽取器。只输出 JSON，不要解释，不要 Markdown。不要固化假设、一次性任务、秘密或未被用户确认的推断。",
                userPrompt
        );
    }

    private String renderPrompt(TerminalMemoryExtractionContext context) {
        return """
                从下面一轮对话证据中抽取稳定用户事实/偏好/技术栈/历史决策。
                JSON schema:
                {"schema":"springclaw.semantic-memory-extraction.v1","candidates":[{"kind":"USER_PREFERENCE|TECH_STACK|REPORTING_RELATIONSHIP|HISTORICAL_DECISION|WORKFLOW_PREFERENCE|NEGATIVE_PREFERENCE","content":"...","subject":"user","scopeType":"PERSONAL_USER","importance":0.0,"confidence":0.0,"sourceEventKeys":["..."],"sourceRunId":"%s","reason":"...","hypothetical":false}]}
                若没有可记忆内容，输出 {"schema":"springclaw.semantic-memory-extraction.v1","candidates":[]}

                证据：
                %s
                """.formatted(context.runId(), renderEvidence(context));
    }

    private String renderEvidence(TerminalMemoryExtractionContext context) {
        StringBuilder sb = new StringBuilder();
        for (MemorySourceEvent event : context.events()) {
            sb.append("- eventKey=").append(event.eventKey())
                    .append(", role=").append(event.role())
                    .append(", type=").append(event.eventType())
                    .append(", content=").append(event.content())
                    .append('\n');
        }
        return sb.toString();
    }
}
