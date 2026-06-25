package com.springclaw.service.chat.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class ConversationAdvisorSupportCanonicalModeTest {

    @Test
    void canonicalModeConstructsWithoutChangingDefaultAdvisorApi() {
        ConversationAdvisorSupport support = new ConversationAdvisorSupport(
                null,
                mock(SemanticMemoryAdvisor.class),
                false,
                true
        );

        assertThat(support).isNotNull();
    }

    @Test
    void canonicalModeSuppressesAllRetrievalAdvisorsEvenWhenChatMemoryFlagIsOn() {
        MessageChatMemoryAdvisor messageAdvisor = messageAdvisor();
        SemanticMemoryAdvisor semanticAdvisor = mock(SemanticMemoryAdvisor.class);
        ConversationAdvisorSupport support = new ConversationAdvisorSupport(
                messageAdvisor,
                semanticAdvisor,
                true,
                true
        );

        AdvisorApplication application = applyAdvisors(support);

        assertThat(application.advisors()).isEmpty();
        assertThat(application.params())
                .containsEntry("chat_memory_conversation_id", "session-1")
                .containsEntry(SemanticMemoryAdvisor.CONTEXT_USER_ID, "user-1");
    }

    private AdvisorApplication applyAdvisors(ConversationAdvisorSupport support) {
        ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = mapCaptor();
        ArgumentCaptor<List<Advisor>> advisorsCaptor = advisorListCaptor();
        when(advisorSpec.params(paramsCaptor.capture())).thenReturn(advisorSpec);
        when(advisorSpec.advisors(advisorsCaptor.capture())).thenReturn(advisorSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.AdvisorSpec> customizer = invocation.getArgument(0);
            customizer.accept(advisorSpec);
            return requestSpec;
        });

        ChatClient.ChatClientRequestSpec returned = support.apply(
                requestSpec,
                "session-1",
                "user-1"
        );

        assertThat(returned).isSameAs(requestSpec);
        return new AdvisorApplication(
                advisorsCaptor.getValue(),
                paramsCaptor.getValue()
        );
    }

    private MessageChatMemoryAdvisor messageAdvisor() {
        return MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .maxMessages(20)
                        .build()
        ).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<Advisor>> advisorListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private record AdvisorApplication(
            List<Advisor> advisors,
            Map<String, Object> params
    ) {
    }
}
