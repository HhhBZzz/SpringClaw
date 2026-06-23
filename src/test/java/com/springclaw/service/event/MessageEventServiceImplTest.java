package com.springclaw.service.event;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.impl.MessageEventServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageEventServiceImplTest {

    @Test
    void shouldRecordAndQueryLocalEventsWhenDbDisabled() {
        MessageEventServiceImpl service = new MessageEventServiceImpl(false);

        service.recordTurn("s-evt", "api", "u1", "hello", "hi", "CHAT", "req1");
        var list = service.listRecent("s-evt", 10);

        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals("USER", list.get(0).getRole());
        Assertions.assertEquals("ASSISTANT", list.get(1).getRole());
    }

    @Test
    void shouldFilterLocalSessionEventsByUserId() {
        MessageEventServiceImpl service = new MessageEventServiceImpl(false);

        service.recordTurn("s-evt", "api", "u1", "hello", "hi", "CHAT", "req1");
        service.recordSingle("s-evt", "api", "u2", "USER", "CHAT", "hidden", "req2");

        var list = service.listSessionEvents("s-evt", "u1", null, "CHAT", 10, true);

        Assertions.assertEquals(2, list.size());
        Assertions.assertTrue(list.stream().allMatch(event -> "u1".equals(event.getUserId())));
        Assertions.assertEquals(3L, service.countSessionEvents("s-evt", null, null, "CHAT"));
        Assertions.assertEquals(2L, service.countSessionEvents("s-evt", "u1", null, "CHAT"));
    }

    @Test
    void appendWithSameEventKeyIsIdempotent() {
        MessageEventServiceImpl service = new MessageEventServiceImpl(false);
        MessageEventWrite write = new MessageEventWrite(
                "chat:req-1:user", "s1", "api", "u1", "USER", "CHAT", "hello", "req-1");

        MessageEventReceipt first = service.append(write);
        MessageEventReceipt second = service.append(write);

        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.eventKey()).isEqualTo("chat:req-1:user");
        assertThat(service.listSessionEvents("s1", "u1", null, "CHAT", 10, true))
                .hasSize(1);
    }

    @Test
    void appendWithDistinctEventKeysCreatesDistinctRows() {
        MessageEventServiceImpl service = new MessageEventServiceImpl(false);
        service.append(new MessageEventWrite(
                "chat:req-1:user", "s1", "api", "u1", "USER", "CHAT", "hello", "req-1"));
        service.append(new MessageEventWrite(
                "chat:req-1:assistant:terminal", "s1", "api", "u1",
                "ASSISTANT", "CHAT", "answer", "req-1"));

        List<MessageEvent> events = service.listSessionEvents(
                "s1", "u1", null, "CHAT", 10, true);
        assertThat(events).hasSize(2);
    }

    @Test
    void recordSingleRemainsCompatibleWithNonMemoryEventKey() {
        MessageEventServiceImpl service = new MessageEventServiceImpl(false);
        service.recordSingle("s1", "api", "u1", "SYSTEM", "OPAR", "PLAN=...", "req-1");
        service.recordSingle("s1", "api", "u1", "SYSTEM", "OPAR", "PLAN=...", "req-1");

        List<MessageEvent> events = service.listSessionEvents(
                "s1", "u1", null, "OPAR", 10, true);
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(evt ->
                assertThat(evt.getEventKey()).doesNotStartWith("chat:"));
    }
}
