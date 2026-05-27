package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalExecutionNarratorTest {

    private final ChatResponsePolicyService policyService = new ChatResponsePolicyService("");
    private final ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
    private final LocalExecutionNarrator narrator = new LocalExecutionNarrator(policyService, modelCallExecutor);

    @Test
    void shouldReturnNarratedAnswerWhenModelCallSucceeds() throws Exception {
        AiProviderService.ActiveChatClient activeClient = activeClient();
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
        AiProviderService.ActiveChatClient activeClient = activeClient();
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
    void shouldNarrateWorkspaceAnswerWhenModelIsAvailable() throws Exception {
        AiProviderService.ActiveChatClient activeClient = activeClient();
        when(modelCallExecutor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "整理后的项目结构说明。",
                        activeClient,
                        List.of("qwen3.5-plus"),
                        false
                ));

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

        assertThat(answer).isEqualTo("整理后的项目结构说明。");
        verify(modelCallExecutor).executeChat(any(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void shouldFallBackToDeterministicWorkspaceAnswerWhenModelNarrationFails() throws Exception {
        AiProviderService.ActiveChatClient activeClient = activeClient();
        when(modelCallExecutor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new SocketTimeoutException("Read timed out"));

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
    }

    @Test
    void shouldReturnDeterministicControlPlaneAnswerWithoutModelNarration() {
        AiProviderService.ActiveChatClient activeClient = activeClient();

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

    @Test
    void shouldReturnDeterministicLocalFileAnswerWithoutModelNarration() {
        AiProviderService.ActiveChatClient activeClient = activeClient();

        String answer = narrator.narrate(
                "system",
                assembled("授权桌面全部"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "LOCAL_FILES_DESKTOP_LIST",
                        "[F] root1:Desktop/时间序列试卷_完整试卷带答案清晰版.docx",
                        "### 结论\n桌面在当前授权范围内。\n\n### 桌面文件清单\n| 序号 | 类型 | 文件名 |\n| --- | --- | --- |\n| 1 | 文件 | 时间序列试卷_完整试卷带答案清晰版.docx |",
                        true
                ),
                activeClient,
                true
        );

        assertThat(answer).contains("桌面在当前授权范围内");
        assertThat(answer).contains("时间序列试卷_完整试卷带答案清晰版.docx");
        verifyNoInteractions(modelCallExecutor);
    }

    private AssembledContext assembled(String question) {
        return new AssembledContext("s1", "api", "u1", question, "", "", question);
    }

    private AiProviderService.ActiveChatClient activeClient() {
        return new AiProviderService.ActiveChatClient(
                "coding-plan",
                "qwen3.5-plus",
                "https://example.invalid/v1",
                mock(ChatClient.class),
                true,
                ""
        );
    }
}
