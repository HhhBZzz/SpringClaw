package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelTransportGuardServiceTest {

    @Test
    void shouldNotDisableWholeProviderForSingleReadTimeout() {
        ModelTransportGuardService guardService = new ModelTransportGuardService(new ChatResponsePolicyService(""), 30);

        guardService.markProviderFailure("deepseek", new SocketTimeoutException("Read timed out"));

        assertThat(guardService.isModelCallEnabled(activeClient())).isTrue();
    }

    @Test
    void shouldTreatPrematureEofAsTransportFailureWithoutProviderCooldown() {
        ModelTransportGuardService guardService = new ModelTransportGuardService(new ChatResponsePolicyService(""), 30);
        RuntimeException failure = new RuntimeException("Premature EOF");

        assertThat(guardService.isTransportFailure(failure)).isTrue();

        guardService.markProviderFailure("deepseek", failure);

        assertThat(guardService.isModelCallEnabled(activeClient())).isTrue();
    }

    @Test
    void shouldReportRecentPrematureEofInsteadOfConfigurationIssue() {
        ModelTransportGuardService guardService = new ModelTransportGuardService(new ChatResponsePolicyService(""), 30);

        guardService.markProviderFailure("deepseek", new RuntimeException("Premature EOF"));

        assertThat(guardService.disabledModelReason(activeClient()))
                .contains("上游模型服务连接中断")
                .doesNotContain("未配置可用模型提供方");
    }

    private AiProviderService.ActiveChatClient activeClient() {
        return new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );
    }
}
