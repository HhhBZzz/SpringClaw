package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityExecutor;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.SystemHealthToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SystemHealthCapabilityExecutor extends AbstractCapabilityExecutor implements CapabilityExecutor {

    private final AiProviderService aiProviderService;
    private final SystemHealthToolPack systemHealthToolPack;
    private final SystemToolPack systemToolPack;

    public SystemHealthCapabilityExecutor(AiProviderService aiProviderService,
                                          SystemHealthToolPack systemHealthToolPack,
                                          SystemToolPack systemToolPack) {
        this.aiProviderService = aiProviderService;
        this.systemHealthToolPack = systemHealthToolPack;
        this.systemToolPack = systemToolPack;
    }

    @Override
    public String toolset() {
        return "system-health";
    }

    @Override
    public boolean supports(AgentDecision decision) {
        return intent(decision, "model_control");
    }

    @Override
    public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
        List<CapabilityResult> results = new ArrayList<>();
        ToolExecutionContext context = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "AGENT-RUNTIME",
                requestId,
                null
        );
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            results.add(run("system-health", toolset(), "read", "检查 Spring Boot 运行健康状态", systemHealthToolPack::runtimeHealth));
            results.add(run("model-status", toolset(), "read", "读取当前模型状态", this::modelStatus));
            results.add(run("system.jvm", toolset(), "read", "读取 JVM 状态", systemToolPack::jvmInfo));
            results.add(run("system.time", toolset(), "read", "读取系统时间", systemToolPack::now));
        }
        return results;
    }

    private String modelStatus() {
        AiProviderService.ActiveChatClient client = aiProviderService.activeClient();
        return "provider=" + client.providerId()
                + "\nmodel=" + client.model()
                + "\navailable=" + client.available()
                + "\nbaseUrl=" + client.baseUrl()
                + "\nunavailableReason=" + client.unavailableReason();
    }
}
