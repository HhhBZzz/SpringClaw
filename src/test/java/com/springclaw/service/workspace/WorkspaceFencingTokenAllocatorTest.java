package com.springclaw.service.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class WorkspaceFencingTokenAllocatorTest {

    @Autowired
    WorkspaceFencingTokenAllocator allocator;

    @Test
    void allocatedTokensAreDurableAndGloballyIncreasing() {
        String workspace = UUID.randomUUID().toString().replace("-", "");

        long first = allocator.nextToken(workspace, "proposal-1");
        long second = allocator.nextToken(workspace, "proposal-2");

        assertThat(second).isGreaterThan(first);
    }
}
