package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimplifiedOparEngineTest {

    @Test
    void shouldShortCircuitExplicitWebFetchToPriorityStructuredLocalSkill() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        LocalExecutionNarrator localExecutionNarrator = mock(LocalExecutionNarrator.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        OparContextAwareSupport contextAwareSupport = mock(OparContextAwareSupport.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);

        LocalExecutionSupport localExecutionSupport = mock(LocalExecutionSupport.class);

        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                aiProviderService,
                toolOrchestrator,
                localSkillFallbackService,
                localExecutionNarrator,
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                conversationAdvisorSupport,
                mock(ChatResponsePolicyService.class),
                localExecutionSupport
        );

        AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "coding-plan",
                "qwen3.5-plus",
                "https://coding.dashscope.aliyuncs.com/v1",
                mock(ChatClient.class),
                true,
                ""
        );
        AssembledContext assembled = new AssembledContext(
                "feishu:p2p:s1",
                "feishu",
                "ou_xxx",
                "读取这个网页 https://example.com",
                "",
                "",
                "# 当前问题\n读取这个网页 https://example.com"
        );
        LocalSkillFallbackService.LocalSkillResult localResult =
                new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN_SKILL:WEB_CRAWL",
                        "skill=web-crawl\nurl=https://example.com\ntitle=Example Domain",
                        "Example Domain",
                        true
                );

        when(contextAwareSupport.tryContextAwareLocalResult(assembled)).thenReturn(null);
        when(localExecutionSupport.tryControlPlane(anyString(), eq(true))).thenReturn(null);
        when(localExecutionSupport.tryPriorityStructured(anyString(), eq(true))).thenReturn(localResult);
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(localExecutionNarrator.narrate(eq("system"), eq(assembled), eq(localResult), eq(activeClient), eq(true)))
                .thenReturn("已读取 Example Domain");

        ChatExecutionResult result = engine.run(
                activeClient,
                "system",
                assembled,
                "req-1",
                (reason, context) -> "fallback:" + reason
        );

        assertThat(result.plan()).contains("SIMPLIFIED:PRIORITY_STRUCTURED");
        assertThat(result.action()).contains("skill=web-crawl");
        assertThat(result.reflect()).isEqualTo("已读取 Example Domain");
        assertThat(result.modelEnabled()).isFalse();
        verifyNoInteractions(modelCallExecutor, toolOrchestrator, conversationAdvisorSupport);
    }

    @Test
    void shouldFallbackToLocalSkillWhenModelReturnsEmptyAnswer() throws Exception {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        LocalExecutionNarrator localExecutionNarrator = mock(LocalExecutionNarrator.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        OparContextAwareSupport contextAwareSupport = mock(OparContextAwareSupport.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        LocalExecutionSupport localExecutionSupport = mock(LocalExecutionSupport.class);

        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                aiProviderService,
                toolOrchestrator,
                localSkillFallbackService,
                localExecutionNarrator,
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                conversationAdvisorSupport,
                chatResponsePolicyService,
                localExecutionSupport
        );

        AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "你好",
                "",
                "",
                "# 当前问题\n你好"
        );

        LocalSkillFallbackService.LocalSkillResult localResult =
                new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN:GREETING",
                        "skill=greeting\nanswer=你好！",
                        "你好！",
                        true
                );

        when(contextAwareSupport.tryContextAwareLocalResult(assembled)).thenReturn(null);
        when(localExecutionSupport.tryControlPlane(anyString(), eq(true))).thenReturn(null);
        when(localExecutionSupport.tryPriorityStructured(anyString(), eq(true))).thenReturn(null);
        when(localExecutionSupport.tryFallback(anyString(), eq(true))).thenReturn(localResult);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(modelCallExecutor.executeChat(
                eq(activeClient),
                eq("simplified-answer"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any()
        )).thenReturn(new ModelCallExecutor.ModelCallResult<>(
                "",
                activeClient,
                List.of("deepseek:deepseek-v4-pro"),
                false
        ));
        when(chatResponsePolicyService.looksLikeHallucinatedXmlToolCall(anyString())).thenReturn(false);
        when(localExecutionNarrator.narrate(eq("system"), eq(assembled), eq(localResult), any(), anyBoolean()))
                .thenReturn("你好！有什么可以帮助你的吗？");

        ChatExecutionResult result = engine.run(
                activeClient,
                "system",
                assembled,
                "req-1",
                (reason, context) -> "fallback:" + reason
        );

        assertThat(result.plan()).contains("LOCAL_FALLBACK");
        assertThat(result.reflect()).isEqualTo("你好！有什么可以帮助你的吗？");
    }
}