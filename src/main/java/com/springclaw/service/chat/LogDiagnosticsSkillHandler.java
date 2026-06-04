package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
public class LogDiagnosticsSkillHandler implements BuiltinSkillHandler {

    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final LocalSkillQuerySupport querySupport;

    public LogDiagnosticsSkillHandler(WorkspaceSearchToolPack workspaceSearchToolPack,
                                      ScriptSkillExecutorService scriptSkillExecutorService,
                                      ScriptSkillCatalogService scriptSkillCatalogService) {
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.querySupport = new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService);
    }

    @Override
    public String skillId() {
        return "log-diagnostics";
    }

    @Override
    public Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
        String answer = querySupport.runScriptSkillByCategory("runtime", question);
        if (!StringUtils.hasText(answer)) {
            answer = workspaceSearchToolPack.analyzeWorkspaceTask(question);
        }
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return Optional.of(new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:LOG_DIAGNOSTICS",
                detail,
                answer,
                true
        ));
    }
}
