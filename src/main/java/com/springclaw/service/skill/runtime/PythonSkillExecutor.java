package com.springclaw.service.skill.runtime;

import com.springclaw.common.util.TextUtils;
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
        String type = TextUtils.normalize(definition == null ? "" : definition.executorType());
        return "python".equals(type) || "script".equals(type);
    }

    @Override
    public String execute(SkillDefinition definition, String inputPayload) {
        String payload = TextUtils.safe(inputPayload);
        if (!StringUtils.hasText(payload)) {
            return scriptSkillExecutorService.runScriptSkillByGoal(definition.skillId(), "请执行默认任务");
        }
        if (looksLikeJson(payload)) {
            return scriptSkillExecutorService.runScriptSkill(definition.skillId(), payload);
        }
        return scriptSkillExecutorService.runScriptSkillByGoal(definition.skillId(), payload);
    }

    private boolean looksLikeJson(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false;
        }
        try {
            // 严格解析：只有合法 JSON object 才走 args 路径，避免 "{整理BTC价格}" 等自然语言被误判为 JSON
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed).isObject();
        } catch (Exception ex) {
            return false;
        }
    }

}
