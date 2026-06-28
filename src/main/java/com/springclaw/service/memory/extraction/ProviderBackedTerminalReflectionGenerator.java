package com.springclaw.service.memory.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ProviderBackedTerminalReflectionGenerator implements TerminalReflectionGenerator {

    private final ProviderMemoryModelClient modelClient;
    private final StrictJsonParser jsonParser;
    private final String reflectionProviderId;
    private final String fallbackProviderId;

    ProviderBackedTerminalReflectionGenerator(
            ProviderMemoryModelClient modelClient,
            StrictJsonParser jsonParser,
            @Value("${springclaw.memory.semantic-extraction.reflection-provider-id:primary}")
            String reflectionProviderId,
            @Value("${springclaw.memory.semantic-extraction.fallback-provider-id:deepseek}")
            String fallbackProviderId
    ) {
        this.modelClient = modelClient;
        this.jsonParser = jsonParser;
        this.reflectionProviderId = reflectionProviderId;
        this.fallbackProviderId = fallbackProviderId;
    }

    @Override
    public TerminalReflectionResult reflect(TerminalMemoryExtractionContext context) throws Exception {
        String first = call(context, renderPrompt(context));
        try {
            return jsonParser.parseObject(first, TerminalReflectionResult.class);
        } catch (Exception parseFailure) {
            String repaired = call(context, "上一次输出不是合法 JSON。请只输出 terminal-reflection.v1 JSON。");
            return jsonParser.parseObject(repaired, TerminalReflectionResult.class);
        }
    }

    private String call(TerminalMemoryExtractionContext context, String userPrompt) throws Exception {
        return modelClient.call(
                reflectionProviderId,
                fallbackProviderId,
                "memory-terminal-reflection",
                context,
                "你是终态反思器。只输出 JSON。反思必须可复用且必须引用证据，不能自我夸大。",
                userPrompt
        );
    }

    private String renderPrompt(TerminalMemoryExtractionContext context) {
        return """
                基于本轮终态证据，产出最多一条可复用的运行教训。没有可复用教训时输出空 lesson。
                JSON schema:
                {"schema":"springclaw.terminal-reflection.v1","outcome":"SUCCESS|FAILED|DEGRADED|UNKNOWN","lesson":"...","applicability":"...","failureMode":"","evidenceRefs":["run:%s","event:<eventKey>"],"confidence":0.0}

                证据：
                %s
                """.formatted(context.runId(), renderEvidence(context));
    }

    private String renderEvidence(TerminalMemoryExtractionContext context) {
        StringBuilder sb = new StringBuilder();
        for (MemorySourceEvent event : context.events()) {
            sb.append("- event:").append(event.eventKey())
                    .append(" [").append(event.role()).append("] ")
                    .append(event.content())
                    .append('\n');
        }
        return sb.toString();
    }
}
