package com.springclaw.service.skill.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.chat.BuiltinSkillExecutionService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 统一 skill 执行入口。
 */
@Service
public class SkillRuntimeService {

    private final ScriptSkillExecutorService scriptSkillExecutorService;
    private final BuiltinSkillExecutionService builtinSkillExecutionService;
    private final SkillRegistryService skillRegistryService;

    public SkillRuntimeService(ScriptSkillExecutorService scriptSkillExecutorService,
                               BuiltinSkillExecutionService builtinSkillExecutionService) {
        this(scriptSkillExecutorService, builtinSkillExecutionService, null);
    }

    @Autowired
    public SkillRuntimeService(ScriptSkillExecutorService scriptSkillExecutorService,
                               BuiltinSkillExecutionService builtinSkillExecutionService,
                               SkillRegistryService skillRegistryService) {
        this.scriptSkillExecutorService = scriptSkillExecutorService;
        this.builtinSkillExecutionService = builtinSkillExecutionService;
        this.skillRegistryService = skillRegistryService;
    }

    public String executeBySkillId(String skillId, String inputPayload, Set<String> allowedToolPacks) {
        if (!StringUtils.hasText(skillId)) {
            throw new BusinessException(40084, "skillId 不能为空");
        }
        String normalizedSkillId = skillId.trim();
        if (!normalizedSkillId.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            throw new BusinessException(40097, "skillName 非法，仅支持字母数字_-");
        }
        if (skillRegistryService == null) {
            throw new BusinessException(50097, "skill registry 未配置");
        }
        SkillDefinition definition = skillRegistryService.listAgentVisibleDefinitions(allowedToolPacks).stream()
                .filter(candidate -> normalizedSkillId.equalsIgnoreCase(candidate.skillId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(40362, "当前账号无权执行 skill: " + skillId));
        return execute(definition, inputPayload);
    }

    public String execute(SkillDefinition definition, String inputPayload) {
        if (definition == null) {
            throw new BusinessException(40083, "skill 定义不能为空");
        }
        String payload = safe(inputPayload);
        return switch (normalize(definition.executorType())) {
            case "script", "python" -> executeScriptSkill(definition.skillId(), payload);
            case "builtin" -> executeBuiltinSkill(definition.skillId(), payload);
            default -> throw new BusinessException(40080, "该 skill 当前不可直接执行: " + definition.skillId());
        };
    }

    private String executeScriptSkill(String skillId, String payload) {
        if (!StringUtils.hasText(payload)) {
            return scriptSkillExecutorService.runScriptSkillByGoal(skillId, "请执行默认任务");
        }
        if (looksLikeJson(payload)) {
            return scriptSkillExecutorService.runScriptSkill(skillId, payload);
        }
        return scriptSkillExecutorService.runScriptSkillByGoal(skillId, payload);
    }

    private String executeBuiltinSkill(String skillId, String payload) {
        return builtinSkillExecutionService.executeBySkillId(skillId, payload)
                .map(LocalSkillFallbackService.LocalSkillResult::fallbackAnswer)
                .orElseThrow(() -> new BusinessException(50096, "builtin skill 未返回结果: " + skillId));
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
