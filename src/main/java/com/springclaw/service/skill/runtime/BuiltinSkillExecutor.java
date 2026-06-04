package com.springclaw.service.skill.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.chat.BuiltinSkillExecutionService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.skill.SkillDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Executes Java builtin skills through the builtin handler registry.
 */
@Component
public class BuiltinSkillExecutor implements SkillExecutor {

    private final BuiltinSkillExecutionService builtinSkillExecutionService;

    public BuiltinSkillExecutor(BuiltinSkillExecutionService builtinSkillExecutionService) {
        this.builtinSkillExecutionService = builtinSkillExecutionService;
    }

    @Override
    public boolean supports(SkillDefinition definition) {
        return "builtin".equals(normalize(definition == null ? "" : definition.executorType()));
    }

    @Override
    public String execute(SkillDefinition definition, String inputPayload) {
        return builtinSkillExecutionService.executeBySkillId(definition.skillId(), inputPayload)
                .map(LocalSkillFallbackService.LocalSkillResult::fallbackAnswer)
                .orElseThrow(() -> new BusinessException(50096, "builtin skill 未返回结果: " + definition.skillId()));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
