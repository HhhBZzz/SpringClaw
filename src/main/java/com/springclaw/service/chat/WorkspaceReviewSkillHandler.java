package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.workspace.WorkspaceReviewService;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class WorkspaceReviewSkillHandler implements BuiltinSkillHandler {

    private final WorkspaceReviewService workspaceReviewService;

    public WorkspaceReviewSkillHandler(WorkspaceReviewService workspaceReviewService) {
        this.workspaceReviewService = workspaceReviewService;
    }

    @Override
    public String skillId() {
        return "workspace-review";
    }

    @Override
    public Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
        String answer = workspaceReviewService.reviewWorkspace(question);
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return Optional.of(new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:WORKSPACE_REVIEW",
                detail,
                answer,
                true
        ));
    }
}
