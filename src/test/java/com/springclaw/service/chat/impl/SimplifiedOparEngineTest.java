package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentCapabilityExecutionService;
import com.springclaw.service.agent.AgentCapabilityResult;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.tool.runtime.ToolOrchestrator;
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
    void shouldExecuteReadCapabilitiesBeforeModelWhenDecisionNeedsAgentTools() throws Exception {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        LocalExecutionNarrator localExecutionNarrator = mock(LocalExecutionNarrator.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        OparContextAwareSupport contextAwareSupport = mock(OparContextAwareSupport.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        AgentCapabilityExecutionService capabilityExecutionService = mock(AgentCapabilityExecutionService.class);

        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                aiProviderService,
                toolOrchestrator,
                localSkillFallbackService,
                localExecutionNarrator,
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                conversationAdvisorSupport,
                messageEventService,
                capabilityExecutionService,
                mock(ChatResponsePolicyService.class),
                mock(LocalExecutionSupport.class)
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
                "分析当前项目结构",
                "",
                "",
                "# 当前问题\n分析当前项目结构"
        );
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search", "workspace-review", "file", "skill-library"),
                "read",
                false,
                "检测到项目分析意图"
        );

        when(contextAwareSupport.tryContextAwareLocalResult(assembled)).thenReturn(null);
        when(localSkillFallbackService.tryHandleControlPlane(anyString())).thenReturn(Optional.empty());
        when(localSkillFallbackService.tryHandlePriorityStructured(anyString())).thenReturn(Optional.empty());
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(capabilityExecutionService.execute(decision, assembled, "req-1"))
                .thenReturn(List.of(new AgentCapabilityResult(
                        "workspace-review",
                        "success",
                        "已审查当前工作区",
                        "LOCAL_WORKSPACE_REVIEW\n根目录: /Users/hanbingzheng/springclaw"
                )));
        when(modelCallExecutor.executeChat(
                eq(activeClient),
                eq("simplified-answer"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any()
        )).thenReturn(new ModelCallExecutor.ModelCallResult<>(
                "项目是 Spring Boot + Spring AI Agent Runtime。",
                activeClient,
                List.of("deepseek:deepseek-v4-pro"),
                false
        ));

        ChatExecutionResult result = engine.run(
                activeClient,
                "system",
                assembled,
                "req-1",
                (reason, context) -> "fallback:" + reason,
                decision
        );

        assertThat(result.action()).contains("主动执行能力数量=1");
        assertThat(result.action()).contains("workspace-review");
        assertThat(result.action()).contains("LOCAL_WORKSPACE_REVIEW");
        assertThat(result.reflect()).isEqualTo("项目是 Spring Boot + Spring AI Agent Runtime。");
    }

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
        MessageEventService messageEventService = mock(MessageEventService.class);

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
                messageEventService,
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
        verifyNoInteractions(modelCallExecutor, toolOrchestrator, conversationAdvisorSupport, messageEventService);
    }

    @Test
    void shouldReturnToolAuditFallbackWhenModelTimesOutAfterToolSuccess() throws Exception {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
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
                localExecutionNarrator,
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                conversationAdvisorSupport,
                messageEventService,
                mock(ChatResponsePolicyService.class),
                mock(LocalExecutionSupport.class)
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
                .thenReturn(List.of(toolSuccess("req-1", "FileToolPack.listFiles", "[F] skills/runtime_probe/scripts/run.py")));

        ChatExecutionResult result = engine.run(
                activeClient,
                "system",
                assembled,
                "req-1",
                (reason, context) -> "fallback:" + reason
        );

        assertThat(result.reflect()).contains("我已经拿到本地工具结果");
        assertThat(result.reflect()).doesNotContain("远程模型");
        assertThat(result.reflect()).doesNotContain("Error while extracting response");
        assertThat(result.reflect()).contains("文件检索");
        assertThat(result.reflect()).contains("skills/runtime_probe/scripts/run.py");
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
