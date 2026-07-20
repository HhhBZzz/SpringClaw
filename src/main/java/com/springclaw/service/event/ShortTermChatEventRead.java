package com.springclaw.service.event;

import com.springclaw.domain.entity.MessageEvent;

import java.util.List;
import java.util.Objects;

public record ShortTermChatEventRead(
        List<MessageEvent> events,
        Source source
) {
    public enum Source {
        DURABLE,
        LOCAL_FALLBACK
    }

    public ShortTermChatEventRead {
        events = events == null ? List.of() : List.copyOf(events);
        source = Objects.requireNonNull(source, "source");
    }
}
