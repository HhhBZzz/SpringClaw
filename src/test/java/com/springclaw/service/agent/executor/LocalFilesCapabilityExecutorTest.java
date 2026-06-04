package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalFilesCapabilityExecutorTest {

    @Test
    void shouldListDesktopDirectoryForDesktopListingQuestion() {
        LocalFilesystemToolPack toolPack = mock(LocalFilesystemToolPack.class);
        when(toolPack.listAuthorizedRoots()).thenReturn("root1: /Users/hanbingzheng");
        when(toolPack.listAuthorizedFiles("root1", "Desktop")).thenReturn("[F] root1:Desktop/resume.pdf");
        LocalFilesCapabilityExecutor executor = new LocalFilesCapabilityExecutor(toolPack);

        List<CapabilityResult> results = executor.execute(
                new AgentDecision("local_files", "agent_tools", List.of("local-files", "file"), "read", false, "本地文件"),
                new AssembledContext("s1", "api", "u1", "看看桌面上有什么文件", "", "", ""),
                "req-1"
        );

        assertThat(results).extracting(CapabilityResult::capabilityId)
                .contains("local-files.roots", "local-files.list-desktop");
        assertThat(results).extracting(CapabilityResult::payload)
                .anySatisfy(payload -> assertThat(payload).contains("Desktop/resume.pdf"));
        verify(toolPack).listAuthorizedFiles("root1", "Desktop");
    }
}
