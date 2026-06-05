package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextualFollowUpQuestionResolverTest {

    @Test
    void shouldRewriteWeatherCityFollowUpUsingRecentWeatherContext() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        when(messageEventService.listRecent("s1", 8)).thenReturn(List.of(
                event("USER", "CHAT", "哈尔滨现在温度"),
                event("ASSISTANT", "CHAT", "结论：哈尔滨当前天气：局部多云，温度 14.5℃，湿度 78%。\nweather.current [success]")
        ));
        ContextualFollowUpQuestionResolver resolver = new ContextualFollowUpQuestionResolver(messageEventService);

        String resolved = resolver.resolve("s1", "北京呢");

        assertThat(resolved).isEqualTo("北京现在温度");
    }

    @Test
    void shouldKeepFollowUpUnchangedWhenRecentContextIsNotWeather() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        when(messageEventService.listRecent("s1", 8)).thenReturn(List.of(
                event("USER", "CHAT", "介绍一下哈尔滨的历史"),
                event("ASSISTANT", "CHAT", "哈尔滨是一座具有近现代城市发展特色的城市。")
        ));
        ContextualFollowUpQuestionResolver resolver = new ContextualFollowUpQuestionResolver(messageEventService);

        String resolved = resolver.resolve("s1", "北京呢");

        assertThat(resolved).isEqualTo("北京呢");
    }

    @Test
    void shouldKeepExplicitWeatherQuestionUnchanged() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        ContextualFollowUpQuestionResolver resolver = new ContextualFollowUpQuestionResolver(messageEventService);

        String resolved = resolver.resolve("s1", "北京现在温度");

        assertThat(resolved).isEqualTo("北京现在温度");
        verifyNoInteractions(messageEventService);
    }

    private MessageEvent event(String role, String eventType, String content) {
        MessageEvent event = new MessageEvent();
        event.setSessionKey("s1");
        event.setChannel("api");
        event.setUserId("u1");
        event.setRole(role);
        event.setEventType(eventType);
        event.setContent(content);
        return event;
    }
}
