package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.TurnRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PassThroughContextualResolverTest {

    @Test
    void shouldKeepFollowUpTextUnchangedEvenWhenItLooksLikeAContinuation() {
        PassThroughContextualResolver resolver = new PassThroughContextualResolver();
        TurnContext context = TurnContext.initial(new TurnRequest("s1", "api", "u1", "req-1", "北京呢", "agent"));

        var resolved = resolver.resolve(context);

        assertThat(resolved.text()).isEqualTo("北京呢");
        assertThat(resolved.changed()).isFalse();
        assertThat(resolved.reason()).contains("未启用跨轮槽位继承");
    }
}
