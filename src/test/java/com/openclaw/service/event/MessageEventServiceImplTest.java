package com.openclaw.service.event;

import com.openclaw.service.event.impl.MessageEventServiceImpl;
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
}
