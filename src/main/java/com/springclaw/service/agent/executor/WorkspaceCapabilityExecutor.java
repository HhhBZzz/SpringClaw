package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityExecutor;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.WorkspaceReviewToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WorkspaceCapabilityExecutor extends AbstractCapabilityExecutor implements CapabilityExecutor {

    private final WorkspaceReviewToolPack workspaceReviewToolPack;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;

    public WorkspaceCapabilityExecutor(WorkspaceReviewToolPack workspaceReviewToolPack,
                                       WorkspaceSearchToolPack workspaceSearchToolPack) {
        this.workspaceReviewToolPack = workspaceReviewToolPack;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
    }

    @Override
    public String toolset() {
        return "workspace";
    }

    @Override
    public boolean supports(AgentDecision decision) {
        return intent(decision, "workspace_analysis");
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
            results.add(run("workspace-review", toolset(), "read", "审查当前工作区", () -> workspaceReviewToolPack.reviewWorkspace(assembled.question())));
            results.add(run("workspace-search", toolset(), "read", "定位相关源码与配置", () -> workspaceSearchToolPack.analyzeWorkspaceTask(assembled.question())));
        }
        return results;
    }
}
