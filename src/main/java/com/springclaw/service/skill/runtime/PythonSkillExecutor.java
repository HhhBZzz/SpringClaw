package com.springclaw.service.skill.runtime;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Executes Python/script skills through the existing controlled script runner.
 */
@Component
public class PythonSkillExecutor implements SkillExecutor {

    private final ScriptSkillExecutorService scriptSkillExecutorService;

    public PythonSkillExecutor(ScriptSkillExecutorService scriptSkillExecutorService) {
        this.scriptSkillExecutorService = scriptSkillExecutorService;
    }

    @Override
    public boolean supports(SkillDefinition definition) {
        String type = normalize(definition == null ? "" : definition.executorType());
        return "python".equals(type) || "script".equals(type);
    }

    @Override
    public String execute(SkillDefinition definition, String inputPayload) {
        String payload = safe(inputPayload);
        if (!StringUtils.hasText(payload)) {
            return scriptSkillExecutorService.runScriptSkillByGoal(definition.skillId(), "请执行默认任务");
        }
        if (looksLikeJson(payload)) {
            return scriptSkillExecutorService.runScriptSkill(definition.skillId(), payload);
        }
        return scriptSkillExecutorService.runScriptSkillByGoal(definition.skillId(), payload);
    }

    private boolean looksLikeJson(String text) {
        return StringUtils.hasText(text) && text.trim().startsWith("{") && text.trim().endsWith("}");
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
