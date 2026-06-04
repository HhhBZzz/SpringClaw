package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebCapabilityExecutorTest {

    @Test
    void shouldUseWebCrawlerSkillForExplicitUrlCrawlRequest() {
        WebSearchToolPack webSearchToolPack = mock(WebSearchToolPack.class);
        ScriptSkillToolPack scriptSkillToolPack = mock(ScriptSkillToolPack.class);
        when(scriptSkillToolPack.executeSkillByGoal("web_crawler", "用web_crawler抓取https://example.com"))
                .thenReturn("{\"title\":\"Example Domain\"}");
        WebCapabilityExecutor executor = new WebCapabilityExecutor(webSearchToolPack, scriptSkillToolPack);

        List<CapabilityResult> results = executor.execute(
                new AgentDecision("web_research", "agent_tools", List.of("web"), "read", false, "网页抓取"),
                new AssembledContext("s1", "api", "u1", "用web_crawler抓取https://example.com", "", "", ""),
                "req-1"
        );

        assertThat(results).extracting(CapabilityResult::capabilityId).containsExactly("web.crawler");
        assertThat(results.get(0).payload()).contains("Example Domain");
        verify(scriptSkillToolPack).executeSkillByGoal("web_crawler", "用web_crawler抓取https://example.com");
        verifyNoInteractions(webSearchToolPack);
    }
}
