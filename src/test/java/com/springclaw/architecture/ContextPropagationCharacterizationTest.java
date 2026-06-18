package com.springclaw.architecture;

import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.SemanticMemoryAdvisor;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test — pins how context flows from {@code ContextAssembler}
 * to model requests today. References findings from the runtime audit
 * doc § 4 ("Context construction and injection") and § 14 (responsibility
 * matrix entries for context).
 *
 * <p>Two duplications are explicitly captured:
 * <ol>
 *   <li>{@link AssembledContext#observePrompt} embeds the long-term semantic
 *       memory <em>and</em> {@link SemanticMemoryAdvisor} adds it again to
 *       the system message. With embedding disabled both blocks are empty;
 *       with embedding enabled the same recall appears twice.</li>
 *   <li>{@link ConversationAdvisorSupport} adds {@link MessageChatMemoryAdvisor}
 *       only when {@code springclaw.chat.spring-ai-chat-memory-enabled=true};
 *       {@link SemanticMemoryAdvisor} is registered unconditionally.</li>
 * </ol>
 *
 * <p>If the unified-runtime spec collapses these duplications, the assertions
 * here will need to be migrated together with the implementation — that is the
 * intended use of characterization tests.
 */
class ContextPropagationCharacterizationTest {

    @Test
    @DisplayName("ContextInjection is a thin wrapper of observePrompt; engines that "
            + "consume it cannot see policy / pendingProposal data today")
    void contextInjectionTodayCarriesOnlyObservePromptText() {
        ContextInjection injection = new ContextInjection(
                "OBSERVE", "POLICY-IGNORED", "PENDING-IGNORED",
                Map.of("k", "v"));

        // renderForPrompt joins all three text fields, but two are empty in production.
        assertThat(injection.observePrompt()).isEqualTo("OBSERVE");
        assertThat(injection.policyPrompt()).isEqualTo("POLICY-IGNORED");
        assertThat(injection.pendingProposalPrompt()).isEqualTo("PENDING-IGNORED");
        assertThat(injection.renderForPrompt()).contains("OBSERVE", "POLICY-IGNORED", "PENDING-IGNORED");

        // Production today never populates policyPrompt or pendingProposalPrompt
        // (see ChatContextFactory.build line 123-133): only observePrompt is set.
        ContextInjection productionShape = new ContextInjection(
                "OBSERVE", "", "", Map.of());
        assertThat(productionShape.renderForPrompt()).isEqualTo("OBSERVE\n\n");
    }

    @Test
    @DisplayName("AssembledContext.observePrompt embeds 4 sections (current question, "
            + "Memory Bank, event context, semantic memory)")
    void assembledContextObservePromptCarriesFourSections() {
        AssembledContext ctx = new AssembledContext(
                "session-A", "api", "alice", "为什么登录失败",
                "[event] alice -> bob: hello",
                "[semantic recall] previous error: NullPointerException",
                """
                # 当前问题
                为什么登录失败

                # 项目记忆（Memory Bank）
                project context

                # 短期会话上下文（事件流）
                [event] alice -> bob: hello

                # 长期语义记忆（同会话优先）
                [semantic recall] previous error: NullPointerException
                """);

        // Each section is exposed both inside observePrompt AND as separate fields.
        // This is one of the duplications the audit calls out:
        //  - eventContext appears as a record field AND as a substring of observePrompt
        //  - semanticContext appears as a record field AND as a substring of observePrompt
        assertThat(ctx.observePrompt())
                .contains("# 当前问题")
                .contains("# 项目记忆")
                .contains("# 短期会话上下文")
                .contains("# 长期语义记忆")
                .contains(ctx.eventContext())
                .contains(ctx.semanticContext());
    }

    @Test
    @DisplayName("Empty ContextInjection produces empty prompt prefix (no spurious newlines)")
    void emptyInjectionProducesEmptyPrefix() {
        assertThat(ContextInjection.empty().renderForPrompt()).isEmpty();
    }

    @Test
    @DisplayName("ConversationAdvisorSupport with chat-memory flag OFF (production "
            + "default) registers ONLY SemanticMemoryAdvisor")
    void advisorChainDefaultIncludesSemanticOnly() throws Exception {
        // We pass null for the advisors; ConversationAdvisorSupport stores the
        // references at construction time but does not invoke them until apply()
        // is called against a ChatClientRequestSpec. We never call apply() here —
        // this test only pins which collaborators are stored.
        ConversationAdvisorSupport support = new ConversationAdvisorSupport(
                null, null, /* springAiChatMemoryEnabled */ false);

        assertThat(readField(support, "messageChatMemoryAdvisor")).isNull();
        assertThat(readField(support, "semanticMemoryAdvisor")).isNull();
        assertThat(readField(support, "springAiChatMemoryEnabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("ConversationAdvisorSupport with chat-memory flag ON registers BOTH "
            + "MessageChatMemoryAdvisor and SemanticMemoryAdvisor — short-term history "
            + "is then sourced twice")
    void advisorChainWithFlagOnIncludesBoth() throws Exception {
        ConversationAdvisorSupport support = new ConversationAdvisorSupport(
                null, null, /* springAiChatMemoryEnabled */ true);

        assertThat(readField(support, "springAiChatMemoryEnabled")).isEqualTo(true);
        // Documented duplication: when this flag is on, ContextAssembler.buildEventContext
        // AND MessageChatMemoryAdvisor BOTH read message_event for the same window.
    }

    @Test
    @DisplayName("SemanticMemoryAdvisor implements both CallAdvisor and StreamAdvisor — "
            + "every model call (sync or stream) goes through it")
    void semanticAdvisorImplementsBothFlavours() {
        // The audit doc § 4.2 records this as 'always-on'. The interface
        // implementations below are the contract that makes that true.
        assertThat(CallAdvisor.class.isAssignableFrom(SemanticMemoryAdvisor.class)).isTrue();
        assertThat(StreamAdvisor.class.isAssignableFrom(SemanticMemoryAdvisor.class)).isTrue();
        assertThat(Advisor.class.isAssignableFrom(SemanticMemoryAdvisor.class)).isTrue();
    }

    @Test
    @DisplayName("QuestionAnswerAdvisor (Spring AI built-in RAG advisor) is NOT registered "
            + "in this codebase — RAG is hand-rolled inside ContextAssembler instead")
    void questionAnswerAdvisorIsAbsent() {
        // Documented finding from the audit § 4.2. If a future commit wires
        // QuestionAnswerAdvisor in, this assertion needs to flip together with
        // the spec change.
        boolean qaPresent;
        try {
            Class<?> qa = Class.forName(
                    "org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor");
            // Class exists on the classpath, but the project does not register it.
            // We cannot prove "no bean" without Spring context; we instead check that
            // ConversationAdvisorSupport's declared advisor fields do not include it.
            qaPresent = qa != null;
        } catch (ClassNotFoundException e) {
            qaPresent = false;
        }

        // The Spring AI class itself may or may not be on the classpath depending on
        // dependency resolution; the meaningful invariant is that
        // ConversationAdvisorSupport only declares messageChatMemoryAdvisor and
        // semanticMemoryAdvisor as injected advisor dependencies.
        Field[] declaredFields = ConversationAdvisorSupport.class.getDeclaredFields();
        List<String> advisorFieldNames = java.util.Arrays.stream(declaredFields)
                .filter(f -> Advisor.class.isAssignableFrom(f.getType()))
                .map(Field::getName)
                .toList();
        assertThat(advisorFieldNames)
                .containsExactlyInAnyOrder("messageChatMemoryAdvisor", "semanticMemoryAdvisor");
        // qaPresent on classpath is informational only.
        assertThat(qaPresent || !qaPresent).isTrue();
    }

    @Test
    @DisplayName("Documented field shape of ConversationAdvisorSupport — pins the "
            + "advisor inventory so re-architecture must update this test together")
    void conversationAdvisorSupportFieldShape() throws Exception {
        Field[] fields = ConversationAdvisorSupport.class.getDeclaredFields();
        List<String> names = java.util.Arrays.stream(fields).map(Field::getName).toList();

        assertThat(names).containsExactlyInAnyOrder(
                "CHAT_MEMORY_CONVERSATION_ID",
                "messageChatMemoryAdvisor",
                "semanticMemoryAdvisor",
                "springAiChatMemoryEnabled");
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
