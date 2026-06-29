package com.springclaw.service.chat.impl;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P0 干路上下文注入：验证 5 个引擎的 prompt 渲染方法都把 ContextInjection.renderForPrompt()
 * 注入到 prompt 头部。回归 2026-06-16 事故根因二（5/6 引擎丢失 ContextAssembler 输出）。
 */
class PromptInjectionTest {

    private static final String OBSERVE_MARKER = "🪐MARKER-OBSERVE🪐";
    private static final String QUESTION = "测试问题";
    private static final Instant T0 = Instant.parse("2026-06-18T00:00:00Z");

    private ContextInjection injection() {
        return new ContextInjection(
                "# 当前问题\n" + QUESTION + "\n\n# 短期会话上下文（事件流）\n" + OBSERVE_MARKER,
                "",
                "",
                Map.of()
        );
    }

    private AssembledContext assembled() {
        return new AssembledContext(
                "session:test",
                "api",
                "u1",
                QUESTION,
                "",
                "",
                "# 当前问题\n" + QUESTION
        );
    }

    private ChatContext context() {
        return new ChatContext(
                null,
                "api",
                "u1",
                "USER",
                QUESTION,
                QUESTION,
                "req-1",
                "system",
                assembled(),
                null,
                "simplified",
                "test",
                "agent",
                "general",
                com.springclaw.service.agent.AgentDecision.general("test"),
                injection()
        );
    }

    private ChatContext canonicalContextWithEmptyLegacyInjection() {
        return new ChatContext(
                null,
                "api",
                "u1",
                "USER",
                QUESTION,
                "SNAPSHOT-QUESTION",
                "req-1",
                "system",
                assembled(),
                null,
                "simplified",
                "test",
                "agent",
                "general",
                com.springclaw.service.agent.AgentDecision.general("test"),
                ContextInjection.empty(),
                snapshot()
        );
    }

    @Test
    void basicStreamEngine_prependsInjection() {
        BasicStreamEngine engine = new BasicStreamEngine(
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                mock(ChatResponsePolicyService.class),
                mock(com.springclaw.service.usage.LlmUsageRecordService.class),
                mock(OparLoopEngine.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(com.springclaw.service.guard.ChatGuardService.class),
                null,
                true
        );
        String prompt = engine.renderBasicChatPrompt(context());
        assertThat(prompt).contains(OBSERVE_MARKER);
        assertThat(prompt).contains(QUESTION);
    }

    @Test
    void basicStreamEngine_rendersTypedSnapshotWhenCanonicalContextIsAvailable() {
        BasicStreamEngine engine = new BasicStreamEngine(
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                mock(ChatResponsePolicyService.class),
                mock(com.springclaw.service.usage.LlmUsageRecordService.class),
                mock(OparLoopEngine.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(com.springclaw.service.guard.ChatGuardService.class),
                null,
                true
        );

        String prompt = engine.renderBasicChatPrompt(canonicalContextWithEmptyLegacyInjection());

        assertThat(prompt).contains("SNAPSHOT-QUESTION");
        assertThat(prompt).contains("SNAPSHOT-SHORT-TERM");
        assertThat(prompt).contains("SNAPSHOT-SEMANTIC");
        assertThat(prompt).contains("SNAPSHOT-RULE");
    }

    @Test
    void simplifiedOparEngine_prependsInjection() {
        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                mock(com.springclaw.service.ai.AiProviderService.class),
                mock(com.springclaw.tool.runtime.ToolOrchestrator.class),
                mock(com.springclaw.service.chat.LocalSkillFallbackService.class),
                mock(LocalExecutionNarrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(OparContextAwareSupport.class),
                mock(ConversationAdvisorSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(LocalExecutionSupport.class)
        );
        String prompt = engine.renderUserPrompt(injection(), QUESTION);
        assertThat(prompt).contains(OBSERVE_MARKER);
        assertThat(prompt).contains(QUESTION);
    }

    @Test
    void simplifiedOparEngine_rendersTypedSnapshotWhenCanonicalContextIsAvailable() {
        SimplifiedOparEngine engine = new SimplifiedOparEngine(
                mock(com.springclaw.service.ai.AiProviderService.class),
                mock(com.springclaw.tool.runtime.ToolOrchestrator.class),
                mock(com.springclaw.service.chat.LocalSkillFallbackService.class),
                mock(LocalExecutionNarrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(OparContextAwareSupport.class),
                mock(ConversationAdvisorSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(LocalExecutionSupport.class)
        );

        String prompt = engine.renderUserPrompt(canonicalContextWithEmptyLegacyInjection());

        assertThat(prompt).contains("SNAPSHOT-QUESTION");
        assertThat(prompt).contains("SNAPSHOT-SHORT-TERM");
        assertThat(prompt).contains("SNAPSHOT-SEMANTIC");
        assertThat(prompt).contains("SNAPSHOT-RULE");
    }

    @Test
    void modelLedStreamEngine_prependsInjection() {
        ModelLedStreamEngine engine = new ModelLedStreamEngine(
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                mock(ChatResponsePolicyService.class),
                mock(com.springclaw.service.usage.LlmUsageRecordService.class),
                mock(ModelCallExecutor.class),
                mock(com.springclaw.tool.runtime.ToolOrchestrator.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(com.springclaw.service.guard.ChatGuardService.class),
                null,
                false
        );
        String prompt = engine.renderModelLedPrompt(context());
        assertThat(prompt).contains(OBSERVE_MARKER);
        assertThat(prompt).contains(QUESTION);
    }

    @Test
    void modelLedStreamEngine_rendersTypedSnapshotWhenCanonicalContextIsAvailable() {
        ModelLedStreamEngine engine = new ModelLedStreamEngine(
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                mock(ChatResponsePolicyService.class),
                mock(com.springclaw.service.usage.LlmUsageRecordService.class),
                mock(ModelCallExecutor.class),
                mock(com.springclaw.tool.runtime.ToolOrchestrator.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(com.springclaw.service.guard.ChatGuardService.class),
                null,
                false
        );

        String prompt = engine.renderModelLedPrompt(canonicalContextWithEmptyLegacyInjection());

        assertThat(prompt).contains("SNAPSHOT-QUESTION");
        assertThat(prompt).contains("SNAPSHOT-SHORT-TERM");
        assertThat(prompt).contains("SNAPSHOT-SEMANTIC");
        assertThat(prompt).contains("SNAPSHOT-RULE");
    }

    @Test
    void autonomousLoopEngine_prependsInjection() {
        AutonomousLoopEngine engine = new AutonomousLoopEngine(
                mock(com.springclaw.service.ai.AiProviderService.class),
                mock(com.springclaw.tool.runtime.ToolOrchestrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(com.springclaw.service.guard.ChatGuardService.class),
                null,
                true,
                5
        );
        String prompt = engine.renderAutonomousPrompt(context(), new Object[0], "", "read");
        assertThat(prompt).contains(OBSERVE_MARKER);
        assertThat(prompt).contains(QUESTION);
    }

    @Test
    void autonomousLoopEngine_rendersTypedSnapshotWhenCanonicalContextIsAvailable() {
        AutonomousLoopEngine engine = new AutonomousLoopEngine(
                mock(com.springclaw.service.ai.AiProviderService.class),
                mock(com.springclaw.tool.runtime.ToolOrchestrator.class),
                mock(ModelTransportGuardService.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(SseEventBridge.class),
                mock(ChatResultPersister.class),
                mock(com.springclaw.service.guard.ChatGuardService.class),
                null,
                true,
                5
        );

        String prompt = engine.renderAutonomousPrompt(
                canonicalContextWithEmptyLegacyInjection(),
                new Object[0],
                "",
                "read"
        );

        assertThat(prompt).contains("SNAPSHOT-QUESTION");
        assertThat(prompt).contains("SNAPSHOT-SHORT-TERM");
        assertThat(prompt).contains("SNAPSHOT-SEMANTIC");
        assertThat(prompt).contains("SNAPSHOT-RULE");
    }

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                "req-1",
                "session:test",
                "u1",
                "api",
                "u1",
                "USER",
                QUESTION,
                "SNAPSHOT-QUESTION",
                "system",
                "SNAPSHOT-PROJECT",
                List.of("SNAPSHOT-SHORT-TERM"),
                List.of("SNAPSHOT-SEMANTIC"),
                List.of("SNAPSHOT-RULE"),
                List.of("web"),
                Map.of("providerId", "provider"),
                Map.of("schema", "test"),
                new MemoryFrame(
                        "req-1",
                        MemoryScope.from(SessionAccessClaim.personal(
                                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                                "api",
                                "session:test",
                                "u1"
                        )),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("source", "test"),
                        List.of(),
                        T0,
                        "frame-hash"
                ),
                T0,
                "snapshot-hash"
        );
    }
}
