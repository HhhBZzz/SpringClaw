package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalExecutionNarratorTest {

    private final ChatResponsePolicyService policyService = new ChatResponsePolicyService("");
    private final ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
    private final LocalExecutionNarrator narrator = new LocalExecutionNarrator(policyService, modelCallExecutor);

    @Test
    void shouldReturnNarratedAnswerWhenModelCallSucceeds() throws Exception {
        AiProviderService.ActiveChatClient activeClient = mock(AiProviderService.ActiveChatClient.class);
        when(modelCallExecutor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "这是整理后的自然回答。",
                        activeClient,
                        List.of("qwen3.5-plus"),
                        false
                ));

        String answer = narrator.narrate(
                "system",
                assembled("读取这个网页"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN_SKILL:WEB_CRAWL",
                        "title=Example Domain\ncontent=example",
                        "Example Domain",
                        true
                ),
                activeClient,
                true
        );

        assertThat(answer).isEqualTo("这是整理后的自然回答。");
    }

    @Test
    void shouldFallBackToDeterministicAnswerWhenNarrationTimesOut() throws Exception {
        AiProviderService.ActiveChatClient activeClient = mock(AiProviderService.ActiveChatClient.class);
        when(modelCallExecutor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new SocketTimeoutException("Read timed out"));

        String answer = narrator.narrate(
                "system",
                assembled("读取这个网页"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN_SKILL:WEB_CRAWL",
                        "title=Example Domain\ncontent=example",
                        "Example Domain",
                        true
                ),
                activeClient,
                true
        );

        assertThat(answer).isEqualTo("Example Domain");
    }

    @Test
    void shouldReturnDeterministicWorkspaceAnswerWithoutModelNarration() {
        AiProviderService.ActiveChatClient activeClient = mock(AiProviderService.ActiveChatClient.class);

        String answer = narrator.narrate(
                "system",
                assembled("帮我看看这个项目里结构是怎样的"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN_SKILL:CODE_ANALYSIS",
                        "skill=code-analysis\n项目结构概览\n- src/main/java",
                        "项目结构概览\n- src/main/java",
                        true
                ),
                activeClient,
                true
        );

        assertThat(answer).contains("项目结构概览");
        verifyNoInteractions(modelCallExecutor);
    }

    @Test
    void shouldReturnDeterministicControlPlaneAnswerWithoutModelNarration() {
        AiProviderService.ActiveChatClient activeClient = mock(AiProviderService.ActiveChatClient.class);

        String answer = narrator.narrate(
                "system",
                assembled("切换到 DeepSeek Reasoner"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "MODEL_PROVIDER_SWITCH",
                        "模型切换失败：当前主聊天链路暂不支持该 DeepSeek thinking 模型: deepseek-reasoner。",
                        "模型切换失败：当前主聊天链路暂不支持该 DeepSeek thinking 模型: deepseek-reasoner。",
                        false
                ),
                activeClient,
                true
        );

        assertThat(answer).contains("暂不支持");
        verifyNoInteractions(modelCallExecutor);
    }

    private AssembledContext assembled(String question) {
        return new AssembledContext("s1", "api", "u1", question, "", "", question);
    }
}
