package com.springclaw.service.memory.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.ai.AiProviderService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderBackedTerminalReflectionGeneratorTest {

    @Test
    void repairsParsedButUngroundedReflectionWithEvidenceSpecificPrompt() throws Exception {
        CapturingModelClient modelClient = new CapturingModelClient(
                """
                        {"schema":"springclaw.terminal-reflection.v1","outcome":"SUCCESS","lesson":"","applicability":"","failureMode":"","evidenceRefs":[],"confidence":0.8}
                        """,
                """
                        {"schema":"springclaw.terminal-reflection.v1","outcome":"SUCCESS","lesson":"用户明确要求进度汇报用简短中文时，后续汇报应保持简短中文。","applicability":"后续进度汇报","failureMode":"","evidenceRefs":["run:run-1","event:chat:run-1:user"],"confidence":0.86}
                        """
        );
        ProviderBackedTerminalReflectionGenerator generator =
                new ProviderBackedTerminalReflectionGenerator(
                        modelClient,
                        new StrictJsonParser(new ObjectMapper()),
                        "deepseek",
                        "deepseek"
                );

        TerminalReflectionResult result = generator.reflect(context());

        assertThat(result.lesson()).contains("简短中文");
        assertThat(result.evidenceRefs())
                .containsExactly("run:run-1", "event:chat:run-1:user");
        assertThat(modelClient.userPrompts).hasSize(2);
        assertThat(modelClient.userPrompts.get(1))
                .contains("上一次输出 JSON 合法，但反思内容未通过校验")
                .contains("run:run-1")
                .contains("event:chat:run-1:user")
                .contains("lesson 不能为空");
    }

    @Test
    void repairsReflectionThatOmitsEventGrounding() throws Exception {
        CapturingModelClient modelClient = new CapturingModelClient(
                """
                        {"schema":"springclaw.terminal-reflection.v1","outcome":"SUCCESS","lesson":"后续进度汇报应保持简短中文。","applicability":"后续进度汇报","failureMode":"","evidenceRefs":["run:run-1"],"confidence":0.86}
                        """,
                """
                        {"schema":"springclaw.terminal-reflection.v1","outcome":"SUCCESS","lesson":"用户明确要求进度汇报用简短中文时，后续汇报应保持简短中文。","applicability":"后续进度汇报","failureMode":"","evidenceRefs":["run:run-1","event:chat:run-1:user"],"confidence":0.86}
                        """
        );
        ProviderBackedTerminalReflectionGenerator generator =
                new ProviderBackedTerminalReflectionGenerator(
                        modelClient,
                        new StrictJsonParser(new ObjectMapper()),
                        "deepseek",
                        "deepseek"
                );

        TerminalReflectionResult result = generator.reflect(context());

        assertThat(result.evidenceRefs())
                .containsExactly("run:run-1", "event:chat:run-1:user");
        assertThat(modelClient.userPrompts).hasSize(2);
    }

    private static TerminalMemoryExtractionContext context() {
        return new TerminalMemoryExtractionContext(
                "run-1",
                "session-1",
                "api",
                "alice",
                List.of(
                        new MemorySourceEvent(
                                "chat:run-1:user",
                                "USER",
                                "CHAT",
                                "以后给我进度汇报请用简短中文。"
                        ),
                        new MemorySourceEvent(
                                "chat:run-1:assistant:terminal",
                                "ASSISTANT",
                                "CHAT",
                                "收到，后续进度会用简短中文说明。"
                        )
                )
        );
    }

    private static final class CapturingModelClient extends ProviderMemoryModelClient {
        private final List<String> responses;
        private final List<String> userPrompts = new ArrayList<>();
        private int index;

        private CapturingModelClient(String... responses) {
            super(mock(AiProviderService.class));
            this.responses = List.of(responses);
        }

        @Override
        String call(
                String providerId,
                String fallbackProviderId,
                String source,
                TerminalMemoryExtractionContext context,
                String systemPrompt,
                String userPrompt
        ) {
            userPrompts.add(userPrompt);
            String response = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return response;
        }
    }
}
