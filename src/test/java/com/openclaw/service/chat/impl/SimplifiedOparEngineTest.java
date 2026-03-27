package com.openclaw.service.chat.impl;

import com.openclaw.domain.entity.MessageEvent;
import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.chat.LocalSkillFallbackService;
import com.openclaw.service.context.AssembledContext;
import com.openclaw.service.event.MessageEventService;
import com.openclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimplifiedOparEngineTest {

    @Test
    void shouldShortCircuitExplicitWebFetchToPriorityStructuredLocalSkill() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        ModelControlIntentService modelControlIntentService = mock(ModelControlIntentService.class);
        LocalExecutionNarrator localExecutionNarrator = mock(LocalExecutionNarrator.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        OparContextAwareSupport contextAwareSupport = mock(OparContextAwareSupport.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        MessageEventService messageEventService = mock(MessageEventService.class);

        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                aiProviderService,
                toolOrchestrator,
                localSkillFallbackService,
                modelControlIntentService,
                localExecutionNarrator,
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                conversationAdvisorSupport,
                messageEventService
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
        when(localSkillFallbackService.tryHandleControlPlane(anyString())).thenReturn(Optional.empty());
        when(localSkillFallbackService.tryHandlePriorityStructured(anyString())).thenReturn(Optional.of(localResult));
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
        verifyNoInteractions(modelCallExecutor, toolOrchestrator, modelControlIntentService, conversationAdvisorSupport, messageEventService);
    }

    @Test
    void shouldReturnToolAuditFallbackWhenModelTimesOutAfterToolSuccess() throws Exception {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        ModelControlIntentService modelControlIntentService = mock(ModelControlIntentService.class);
        LocalExecutionNarrator localExecutionNarrator = mock(LocalExecutionNarrator.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        OparContextAwareSupport contextAwareSupport = mock(OparContextAwareSupport.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        MessageEventService messageEventService = mock(MessageEventService.class);

        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                aiProviderService,
                toolOrchestrator,
                localSkillFallbackService,
                modelControlIntentService,
                localExecutionNarrator,
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                conversationAdvisorSupport,
                messageEventService
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
                "用代码分析分析他",
                "- SYSTEM: java.net.SocketTimeoutException: Read timed out",
                "",
                "# 当前问题\n用代码分析分析他"
        );

        when(contextAwareSupport.tryContextAwareLocalResult(assembled)).thenReturn(null);
        when(localSkillFallbackService.tryHandleControlPlane(anyString())).thenReturn(Optional.empty());
        when(localSkillFallbackService.tryHandleStructured(anyString())).thenReturn(Optional.empty());
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(toolOrchestrator.selectAgentTools("feishu", "ou_xxx")).thenReturn(new Object[]{"workspace"});
        doThrow(new RuntimeException("Read timed out")).when(modelCallExecutor).executeChat(
                eq(activeClient),
                eq("simplified-answer"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any()
        );
        when(messageEventService.listSessionEvents("feishu:p2p:s1", "SYSTEM", "TOOL", 24, false))
                .thenReturn(List.of(toolSuccess("req-1", "FileToolPack.listFiles", "[F] skills/runtime_probe.py")));

        ChatExecutionResult result = engine.run(
                activeClient,
                "system",
                assembled,
                "req-1",
                (reason, context) -> "fallback:" + reason
        );

        assertThat(result.reflect()).contains("远程模型在整理最终回答时失败了");
        assertThat(result.reflect()).contains("FileToolPack.listFiles");
        assertThat(result.reflect()).contains("skills/runtime_probe.py");
        assertThat(result.plan()).contains("工具已执行，但模型总结超时");
        assertThat(result.modelEnabled()).isFalse();
    }

    private MessageEvent toolSuccess(String requestId, String toolName, String detail) {
        MessageEvent event = new MessageEvent();
        event.setId(1L);
        event.setRequestId(requestId);
        event.setContent("tool=" + toolName + ", status=SUCCESS, phase=ACT-SIMPLIFIED, detail=" + detail);
        return event;
    }
}
