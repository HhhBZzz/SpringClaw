package com.springclaw.dto.chat;

import com.springclaw.runtime.contract.AgentParadigm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {

    @Test
    void canonicalConstructorCarriesParadigm() {
        ChatRequest req = new ChatRequest("s1", "u1", "hi", "api", "agent", AgentParadigm.OPAR);
        assertThat(req.paradigm()).isEqualTo(AgentParadigm.OPAR);
    }

    @Test
    void fourArgCompatConstructorDefaultsParadigmToNull() {
        ChatRequest req = new ChatRequest("s1", "u1", "hi", "api");
        assertThat(req.paradigm()).isNull();
    }
}
