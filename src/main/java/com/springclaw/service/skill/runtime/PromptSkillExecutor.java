package com.springclaw.service.skill.runtime;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.skill.SkillDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Prompt skills are readable instructions, not directly executable runtime units.
 */
@Component
public class PromptSkillExecutor implements SkillExecutor {

    @Override
    public boolean supports(SkillDefinition definition) {
        return "prompt".equals(TextUtils.normalize(definition == null ? "" : definition.executorType()));
    }

    @Override
    public String execute(SkillDefinition definition, String inputPayload) {
        throw new BusinessException(40080, "该 skill 当前不可直接执行: " + definition.skillId());
    }

}
