package com.springclaw.service.memory.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ProviderBackedSemanticMemoryJudge implements SemanticMemoryJudge {

    private final ProviderMemoryModelClient modelClient;
    private final StrictJsonParser jsonParser;
    private final String judgeProviderId;
    private final String fallbackProviderId;

    ProviderBackedSemanticMemoryJudge(
            ProviderMemoryModelClient modelClient,
            StrictJsonParser jsonParser,
            @Value("${springclaw.memory.semantic-extraction.judge-provider-id:primary}")
            String judgeProviderId,
            @Value("${springclaw.memory.semantic-extraction.fallback-provider-id:deepseek}")
            String fallbackProviderId
    ) {
        this.modelClient = modelClient;
        this.jsonParser = jsonParser;
        this.judgeProviderId = judgeProviderId;
        this.fallbackProviderId = fallbackProviderId;
    }

    @Override
    public SemanticMemoryJudgeVerdict judge(
            SemanticMemoryCandidate candidate,
            TerminalMemoryExtractionContext context
    ) throws Exception {
        String prompt = """
                判断候选记忆是否被证据直接支持，是否是假设性陈述，是否包含秘密或敏感数据。
                只输出 JSON:
                {"schema":"springclaw.semantic-memory-judge.v1","verdict":"ACCEPT|DOWNGRADE_TO_CANDIDATE|REJECT","confidence":0.0,"evidenceGrounded":true,"hypothetical":false,"sensitive":false,"reason":"..."}

                候选:
                %s

                证据:
                %s
                """.formatted(candidate, renderEvidence(context));
        String first = call(context, prompt);
        try {
            return jsonParser.parseObject(first, SemanticMemoryJudgeVerdict.class);
        } catch (Exception parseFailure) {
            String repaired = call(context, "上一次输出不是合法 JSON。请只输出 semantic-memory-judge.v1 JSON。");
            return jsonParser.parseObject(repaired, SemanticMemoryJudgeVerdict.class);
        }
    }

    private String call(TerminalMemoryExtractionContext context, String userPrompt) throws Exception {
        return modelClient.call(
                judgeProviderId,
                fallbackProviderId,
                "memory-semantic-judge",
                context,
                "你是记忆审查器。严格拒绝无证据、假设性、敏感、一次性或未确认推断。",
                userPrompt
        );
    }

    private String renderEvidence(TerminalMemoryExtractionContext context) {
        StringBuilder sb = new StringBuilder();
        for (MemorySourceEvent event : context.events()) {
            sb.append("- ").append(event.eventKey())
                    .append(" [").append(event.role()).append("] ")
                    .append(event.content())
                    .append('\n');
        }
        return sb.toString();
    }
}
