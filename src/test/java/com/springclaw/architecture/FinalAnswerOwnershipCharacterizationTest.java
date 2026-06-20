package com.springclaw.architecture;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.AutonomousLoopEngine;
import com.springclaw.service.chat.impl.BasicStreamEngine;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatContextFactory;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ChatResultPersister;
import com.springclaw.service.chat.impl.ChatServiceImpl;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.MetaGuardExecutor;
import com.springclaw.service.chat.impl.ModelLedStreamEngine;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.chat.impl.OparLoopEngine;
import com.springclaw.service.chat.impl.SimplifiedOparEngine;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterizes final-answer ownership at the concrete
 * {@link ChatServiceImpl#resolveFinalAnswer} boundary.
 */
class FinalAnswerOwnershipCharacterizationTest {

    @Test
    @DisplayName("A non-empty engine reflect result is returned before MetaGuard or policy fallback")
    void nonEmptyReflectReturnsBeforeMetaGuard() throws Throwable {
        Fixture fixture = new Fixture();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe-not-an-answer",
                "plan-not-an-answer",
                "action-not-an-answer",
                "engine-final-answer",
                true
        );

        String answer = invokeResolveFinalAnswer(fixture.service, fixture.context, result);

        assertThat(answer).isEqualTo("engine-final-answer");
        verifyNoInteractions(
                fixture.metaGuardExecutor,
                fixture.responsePolicyService,
                fixture.modelTransportGuardService
        );
    }

    @Test
    @DisplayName("Empty reflect with a model delegates plan and action to MetaGuard")
    void emptyReflectDelegatesPlanAndActionToMetaGuard() throws Throwable {
        Fixture fixture = new Fixture();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe-must-not-feed-final-answer",
                "plan-for-reflect",
                "action-for-reflect",
                "  ",
                true
        );
        when(fixture.metaGuardExecutor.execute(
                fixture.context, "plan-for-reflect", "action-for-reflect"))
                .thenReturn("meta-guard-answer");

        String answer = invokeResolveFinalAnswer(fixture.service, fixture.context, result);

        assertThat(answer).isEqualTo("meta-guard-answer");
        verify(fixture.metaGuardExecutor).execute(
                fixture.context, "plan-for-reflect", "action-for-reflect");
        verify(fixture.metaGuardExecutor, never()).fallbackAnswer(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(fixture.responsePolicyService);
    }

    @Test
    @DisplayName("Empty reflect with a disabled model delegates to the MetaGuard policy fallback")
    void emptyReflectWithDisabledModelDelegatesToFallbackPolicy() throws Throwable {
        Fixture fixture = new Fixture();
        ChatExecutionResult result = new ChatExecutionResult(
                "observe-must-not-feed-fallback",
                "plan-must-not-feed-fallback",
                "action-must-not-feed-fallback",
                "",
                false
        );
        when(fixture.modelTransportGuardService.disabledModelReason(fixture.activeClient))
                .thenReturn("model-disabled");
        when(fixture.metaGuardExecutor.fallbackAnswer("model-disabled", fixture.assembled))
                .thenReturn("policy-fallback-answer");

        String answer = invokeResolveFinalAnswer(fixture.service, fixture.context, result);

        assertThat(answer).isEqualTo("policy-fallback-answer");
        verify(fixture.metaGuardExecutor).fallbackAnswer("model-disabled", fixture.assembled);
        verify(fixture.metaGuardExecutor, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("ChatExecutionResult fields consumed by resolveFinalAnswer remain explicit")
    void chatExecutionResultFieldsFeedingResolutionRemainExplicit() {
        assertThat(ChatExecutionResult.class.isRecord()).isTrue();
        assertThat(Arrays.stream(ChatExecutionResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("observe", "plan", "action", "reflect", "modelEnabled");
    }

    @Test
    @DisplayName("Final-answer and fallback inventory names concrete declaring methods")
    void finalAnswerSitesResolveToActualDeclaredMethods() throws Exception {
        List<MethodSite> sites = List.of(
                new MethodSite(ChatServiceImpl.class, "resolveFinalAnswer",
                        ChatContext.class, ChatExecutionResult.class),
                new MethodSite(ChatServiceImpl.class, "streamReflectAnswer",
                        ChatContext.class, ChatExecutionResult.class, String.class,
                        AtomicBoolean.class, SseEmitter.class, AtomicReference.class),
                new MethodSite(AutonomousLoopEngine.class, "resolveFinalAnswer",
                        ChatExecutionResult.class),
                new MethodSite(BasicStreamEngine.class, "execute",
                        ChatContext.class, AgentEngine.FallbackResponder.class),
                new MethodSite(SimplifiedOparEngine.class, "run",
                        AiProviderService.ActiveChatClient.class, String.class,
                        AssembledContext.class, String.class,
                        AgentEngine.FallbackResponder.class, AgentDecision.class,
                        ContextInjection.class),
                new MethodSite(OparLoopEngine.class, "runLoop",
                        AiProviderService.ActiveChatClient.class, String.class,
                        AssembledContext.class, String.class,
                        AgentEngine.FallbackResponder.class, AgentDecision.class),
                new MethodSite(AgentRuntimeEngine.class, "summarize",
                        ChatContext.class,
                        com.springclaw.service.agent.CapabilityPlan.class,
                        List.class,
                        com.springclaw.service.agent.VerificationResult.class),
                new MethodSite(AgentRuntimeEngine.class, "buildFinalDegradedResult",
                        ChatContext.class,
                        com.springclaw.service.agent.CapabilityPlan.class,
                        List.class,
                        com.springclaw.service.agent.VerificationResult.class,
                        com.springclaw.service.agent.ReflectionResult.class),
                new MethodSite(ModelLedStreamEngine.class, "execute",
                        ChatContext.class, AgentEngine.FallbackResponder.class),
                new MethodSite(ModelLedStreamEngine.class, "handlePartialAnswer",
                        ChatContext.class, StringBuilder.class, Throwable.class,
                        SseEmitter.class, String.class, AtomicBoolean.class),
                new MethodSite(MetaGuardExecutor.class, "execute",
                        ChatContext.class, String.class, String.class),
                new MethodSite(MetaGuardExecutor.class, "normalize",
                        ChatContext.class, String.class),
                new MethodSite(MetaGuardExecutor.class, "fallbackAnswer",
                        String.class, AssembledContext.class),
                new MethodSite(ChatResponsePolicyService.class, "buildPartialAnswerFromAction",
                        String.class, String.class),
                new MethodSite(ChatResponsePolicyService.class, "buildUserFacingFailureReply",
                        String.class, String.class)
        );

        for (MethodSite site : sites) {
            Method method = site.owner().getDeclaredMethod(site.name(), site.parameterTypes());
            assertThat(method.getDeclaringClass())
                    .as("%s.%s must remain declared at the characterized site",
                            site.owner().getSimpleName(), site.name())
                    .isEqualTo(site.owner());
        }
    }

    private static String invokeResolveFinalAnswer(ChatServiceImpl service,
                                                   ChatContext context,
                                                   ChatExecutionResult result) throws Throwable {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "resolveFinalAnswer", ChatContext.class, ChatExecutionResult.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, context, result);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private record MethodSite(Class<?> owner, String name, Class<?>... parameterTypes) {
    }

    private static final class Fixture {

        private final ChatResponsePolicyService responsePolicyService =
                mock(ChatResponsePolicyService.class);
        private final ModelTransportGuardService modelTransportGuardService =
                mock(ModelTransportGuardService.class);
        private final MetaGuardExecutor metaGuardExecutor = mock(MetaGuardExecutor.class);
        private final AssembledContext assembled = new AssembledContext(
                "session-1",
                "api",
                "user-1",
                "question",
                "history",
                "memory",
                "observe"
        );
        private final AiProviderService.ActiveChatClient activeClient =
                new AiProviderService.ActiveChatClient(
                        "provider",
                        "model",
                        "https://example.invalid",
                        mock(ChatClient.class),
                        true,
                        ""
                );
        private final ChatContext context;
        private final ChatServiceImpl service;

        private Fixture() {
            AgentSession session = new AgentSession();
            session.setSessionKey("session-1");
            context = new ChatContext(
                    session,
                    "api",
                    "user-1",
                    "USER",
                    "question",
                    "question",
                    "request-1",
                    "system",
                    assembled,
                    activeClient,
                    "opar",
                    "characterization"
            );
            service = new ChatServiceImpl(
                    mock(AiProviderService.class),
                    mock(ChatGuardService.class),
                    mock(OparLoopEngine.class),
                    mock(SimplifiedOparEngine.class),
                    responsePolicyService,
                    modelTransportGuardService,
                    mock(LlmUsageRecordService.class),
                    mock(ConversationAdvisorSupport.class),
                    mock(ChatContextFactory.class),
                    mock(ChatResultPersister.class),
                    metaGuardExecutor,
                    null,
                    null,
                    mock(AgentRuntimeEngine.class),
                    mock(EngineSelector.class),
                    null,
                    null,
                    null,
                    false,
                    true
            );
        }
    }
}
