package com.springclaw.service.skill.runtime;

import com.springclaw.service.skill.SkillDefinition;

/**
 * Pluggable executor for one family of skill definitions.
 */
public interface SkillExecutor {

    boolean supports(SkillDefinition definition);

    String execute(SkillDefinition definition, String inputPayload);
}
