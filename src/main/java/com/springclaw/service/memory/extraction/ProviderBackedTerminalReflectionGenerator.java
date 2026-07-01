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
            TerminalReflectionResult result =
                    jsonParser.parseObject(first, TerminalReflectionResult.class);
            if (validReflection(context, result)) {
                return result;
            }
            String repaired = call(context, renderSemanticRepairPrompt(context));
            return jsonParser.parseObject(repaired, TerminalReflectionResult.class);
        } catch (Exception parseFailure) {
            String repaired = call(context, renderJsonRepairPrompt(context));
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

    private String renderJsonRepairPrompt(TerminalMemoryExtractionContext context) {
        return """
                上一次输出不是合法 JSON。请只输出 springclaw.terminal-reflection.v1 JSON 对象。
                lesson 不能为空；evidenceRefs 必须包含 run:%s，并至少包含一个下列 event 引用：
                %s
                不要 Markdown，不要解释。
                """.formatted(context.runId(), renderAllowedEvidence(context));
    }

    private String renderSemanticRepairPrompt(TerminalMemoryExtractionContext context) {
        return """
                上一次输出 JSON 合法，但反思内容未通过校验。
                请只输出 springclaw.terminal-reflection.v1 JSON 对象，并满足：
                - lesson 不能为空，必须是可复用教训；
                - evidenceRefs 必须包含 run:%s；
                - evidenceRefs 至少包含一个下列 event 引用；
                - 不允许使用未列出的证据引用；
                - 没有可复用教训时，也要用一句保守 lesson 说明“本轮没有可推广教训”并引用证据。

                可用证据引用：
                %s

                原始证据：
                %s
                """.formatted(context.runId(), renderAllowedEvidence(context), renderEvidence(context));
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

    private String renderAllowedEvidence(TerminalMemoryExtractionContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("- run:").append(context.runId()).append('\n');
        for (MemorySourceEvent event : context.events()) {
            sb.append("- event:").append(event.eventKey()).append('\n');
        }
        return sb.toString();
    }

    private boolean validReflection(
            TerminalMemoryExtractionContext context,
            TerminalReflectionResult reflection
    ) {
        if (reflection == null
                || !TerminalMemoryExtractionService.TERMINAL_REFLECTION_SCHEMA.equals(reflection.schema())
                || reflection.lesson() == null
                || reflection.lesson().isBlank()
                || !Double.isFinite(reflection.confidence())
                || reflection.confidence() < 0.0
                || reflection.confidence() > 1.0
                || reflection.evidenceRefs() == null
                || reflection.evidenceRefs().isEmpty()) {
            return false;
        }
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        allowed.add("run:" + context.runId());
        for (MemorySourceEvent event : context.events()) {
            allowed.add("event:" + event.eventKey());
        }
        return reflection.evidenceRefs().contains("run:" + context.runId())
                && reflection.evidenceRefs().stream().anyMatch(ref -> ref != null && ref.startsWith("event:"))
                && allowed.containsAll(reflection.evidenceRefs());
    }
}
