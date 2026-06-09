package com.springclaw.service.skill.runtime;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.chat.BuiltinSkillExecutionService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 统一 skill 执行入口。
 */
@Service
public class SkillRuntimeService {

    private final List<SkillExecutor> executors;
    private final SkillRegistryService skillRegistryService;

    public SkillRuntimeService(ScriptSkillExecutorService scriptSkillExecutorService,
                               BuiltinSkillExecutionService builtinSkillExecutionService) {
        this(defaultExecutors(scriptSkillExecutorService, builtinSkillExecutionService), null);
    }

    public SkillRuntimeService(ScriptSkillExecutorService scriptSkillExecutorService,
                               BuiltinSkillExecutionService builtinSkillExecutionService,
                               SkillRegistryService skillRegistryService) {
        this(defaultExecutors(scriptSkillExecutorService, builtinSkillExecutionService), skillRegistryService);
    }

    @Autowired
    public SkillRuntimeService(List<SkillExecutor> executors,
                               SkillRegistryService skillRegistryService) {
        this.executors = executors == null ? List.of() : List.copyOf(executors);
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
        return executors.stream()
                .filter(executor -> executor.supports(definition))
                .findFirst()
                .orElseThrow(() -> new BusinessException(40080, "该 skill 当前不可直接执行: " + definition.skillId()))
                .execute(definition, TextUtils.safe(inputPayload));
    }

    private static List<SkillExecutor> defaultExecutors(ScriptSkillExecutorService scriptSkillExecutorService,
                                                        BuiltinSkillExecutionService builtinSkillExecutionService) {
        return List.of(
                new PythonSkillExecutor(scriptSkillExecutorService),
                new BuiltinSkillExecutor(builtinSkillExecutionService),
                new PromptSkillExecutor()
        );
    }
}
