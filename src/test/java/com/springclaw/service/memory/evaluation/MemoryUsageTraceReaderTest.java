package com.springclaw.service.memory.evaluation;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryUsageTraceReaderTest {

    @Test
    void parsesPersistedMemoryUsageTraceEvent() {
        MemoryUsageTraceReader reader = new MemoryUsageTraceReader(mock(MessageEventService.class));

        MemoryUsageTraceView view = reader.parse(
                "run-1",
                "event-1",
                "MEMORY_USAGE=memoryInjected=true, memoryReferencedInAnswer=true, kind=PARAPHRASE, judgedBy=deterministic, refs=[pref-1, pref-2]"
        ).orElseThrow();

        assertThat(view.requestId()).isEqualTo("run-1");
        assertThat(view.memoryInjected()).isTrue();
        assertThat(view.memoryReferencedInAnswer()).isTrue();
        assertThat(view.memoryReferenceKind()).isEqualTo("PARAPHRASE");
        assertThat(view.memoryUseJudgedBy()).isEqualTo("deterministic");
        assertThat(view.referencedSourceIds()).containsExactly("pref-1", "pref-2");
        assertThat(view.sourceEventKey()).isEqualTo("event-1");
    }

    @Test
    void readsLatestTraceForUserScopedRun() {
        MessageEventService eventService = mock(MessageEventService.class);
        MemoryUsageTraceReader reader = new MemoryUsageTraceReader(eventService);
        MessageEvent event = event(
                "run-1",
                "memory-usage-event",
                "MEMORY_USAGE=memoryInjected=true, memoryReferencedInAnswer=false, kind=NONE, judgedBy=deterministic, refs=[]"
        );
        event.setCreateTime(LocalDateTime.parse("2026-06-30T10:00:00"));
        when(eventService.listRequestEvents("run-1", "alice", "SYSTEM", "OPAR", 100, false))
                .thenReturn(List.of(
                        event("run-1", "act-event", "ACT=done"),
                        event
                ));

        MemoryUsageTraceView view = reader.readLatest("run-1", "alice");

        assertThat(view.memoryInjected()).isTrue();
        assertThat(view.memoryReferencedInAnswer()).isFalse();
        assertThat(view.observedAt()).isEqualTo(LocalDateTime.parse("2026-06-30T10:00:00"));
        verify(eventService).listRequestEvents("run-1", "alice", "SYSTEM", "OPAR", 100, false);
    }

    @Test
    void returnsEmptyViewWhenTraceIsMissing() {
        MessageEventService eventService = mock(MessageEventService.class);
        MemoryUsageTraceReader reader = new MemoryUsageTraceReader(eventService);
        when(eventService.listRequestEvents("run-missing", null, "SYSTEM", "OPAR", 100, false))
                .thenReturn(List.of());

        MemoryUsageTraceView view = reader.readLatest("run-missing", null);

        assertThat(view.requestId()).isEqualTo("run-missing");
        assertThat(view.memoryInjected()).isFalse();
        assertThat(view.memoryReferencedInAnswer()).isFalse();
        assertThat(view.memoryReferenceKind()).isEqualTo("NONE");
        assertThat(view.memoryUseJudgedBy()).isEqualTo("unavailable");
    }

    private static MessageEvent event(String requestId, String eventKey, String content) {
        MessageEvent event = new MessageEvent();
        event.setRequestId(requestId);
        event.setEventKey(eventKey);
        event.setRole("SYSTEM");
        event.setEventType("OPAR");
        event.setUserId("alice");
        event.setContent(content);
        return event;
    }
}
