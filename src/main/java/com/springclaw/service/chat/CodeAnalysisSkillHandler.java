package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
public class CodeAnalysisSkillHandler implements BuiltinSkillHandler {

    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final LocalSkillQuerySupport querySupport;

    public CodeAnalysisSkillHandler(WorkspaceSearchToolPack workspaceSearchToolPack,
                                    ScriptSkillExecutorService scriptSkillExecutorService,
                                    ScriptSkillCatalogService scriptSkillCatalogService) {
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.querySupport = new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService);
    }

    @Override
    public String skillId() {
        return "code-analysis";
    }

    @Override
    public Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
        String answer = workspaceSearchToolPack.analyzeWorkspaceTask(question);
        if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
            String scriptAnswer = querySupport.runScriptSkillByCategory("workspace", question);
            if (StringUtils.hasText(scriptAnswer)) {
                answer = scriptAnswer;
            }
        }
        String fallback = querySupport.renderWorkspaceAnswer(answer);
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return Optional.of(new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:CODE_ANALYSIS",
                detail,
                fallback,
                true
        ));
    }
}
