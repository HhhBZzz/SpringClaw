package com.springclaw.tool.pack;

import com.springclaw.service.workspace.WorkspaceReviewService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 本地项目审查工具包。
 *
 * 给 Agent 一个明确的“审查当前工作区”入口，而不是让模型自己猜文件搜索步骤。
 */
@Component
public class WorkspaceReviewToolPack {

    private final WorkspaceReviewService workspaceReviewService;

    public WorkspaceReviewToolPack(WorkspaceReviewService workspaceReviewService) {
        this.workspaceReviewService = workspaceReviewService;
    }

    @Tool(description = "审查当前本地项目源码、架构、风险点、冗余代码和建议阅读顺序。只读取受控项目根目录，不读取系统其他目录")
    public String reviewWorkspace(String goal) {
        return workspaceReviewService.reviewWorkspace(goal);
    }
}
