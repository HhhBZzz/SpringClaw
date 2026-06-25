package com.springclaw.service.chat.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
