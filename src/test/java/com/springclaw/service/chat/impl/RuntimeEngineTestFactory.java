package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.tool.runtime.ToolOrchestrator;

import static org.mockito.Mockito.mock;

/**
 * Typed test-only access to engine constructors whose collaborators include
 * package-private implementation classes.
 */
public final class RuntimeEngineTestFactory {

    private RuntimeEngineTestFactory() {
    }

    public static OparLoopEngine oparLoopEngine(ModelTransportGuardService transportGuard) {
        return new OparLoopEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(LocalSkillFallbackService.class),
                mock(LocalExecutionNarrator.class),
                transportGuard,
                mock(ModelCallExecutor.class),
                mock(OparContextAwareSupport.class),
                mock(OparPromptSupport.class),
                mock(ConversationAdvisorSupport.class),
                mock(LocalExecutionSupport.class),
                true,
                true,
                3
        );
    }

    public static SimplifiedOparEngine simplifiedOparEngine(
            ModelTransportGuardService transportGuard) {
        return new SimplifiedOparEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(LocalSkillFallbackService.class),
                mock(LocalExecutionNarrator.class),
                transportGuard,
                mock(ModelCallExecutor.class),
                mock(OparContextAwareSupport.class),
                mock(ConversationAdvisorSupport.class),
                mock(ChatResponsePolicyService.class),
                mock(LocalExecutionSupport.class)
        );
    }
}
