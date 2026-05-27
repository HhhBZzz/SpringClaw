package com.springclaw.service.event;

import com.springclaw.service.event.impl.MessageEventServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
