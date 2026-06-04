package com.springclaw.service.skill.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.chat.BuiltinSkillExecutionService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRuntimeServiceTest {

    private final ScriptSkillExecutorService scriptExecutor = mock(ScriptSkillExecutorService.class);
    private final BuiltinSkillExecutionService builtinExecutor = mock(BuiltinSkillExecutionService.class);
    private final SkillRuntimeService runtimeService = new SkillRuntimeService(scriptExecutor, builtinExecutor);

    @Test
    void shouldDispatchThroughRegisteredExecutorList() {
        AtomicBoolean executed = new AtomicBoolean(false);
        SkillExecutor customExecutor = new SkillExecutor() {
            @Override
            public boolean supports(SkillDefinition definition) {
                return "custom".equals(definition.executorType());
            }

            @Override
            public String execute(SkillDefinition definition, String inputPayload) {
                executed.set(true);
                return definition.skillId() + ":" + inputPayload;
            }
        };
        SkillRuntimeService runtimeService = new SkillRuntimeService(List.of(customExecutor), null);

        String result = runtimeService.execute(definition("custom_skill", "custom"), "payload");

        assertThat(result).isEqualTo("custom_skill:payload");
        assertThat(executed).isTrue();
    }

    @Test
    void shouldRejectUnknownExecutorTypeWhenNoRegisteredExecutorSupportsIt() {
        SkillRuntimeService runtimeService = new SkillRuntimeService(List.of(), null);

        assertThatThrownBy(() -> runtimeService.execute(definition("unknown_skill", "unknown"), "payload"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("该 skill 当前不可直接执行");
    }

    @Test
    void shouldExecutePythonSkillThroughUnifiedRuntime() {
        SkillDefinition definition = definition("repo_inspector", "python");
        when(scriptExecutor.runScriptSkillByGoal("repo_inspector", "分析项目结构")).thenReturn("结构分析完成");

        String result = runtimeService.execute(definition, "分析项目结构");

        assertThat(result).isEqualTo("结构分析完成");
        verify(scriptExecutor).runScriptSkillByGoal("repo_inspector", "分析项目结构");
    }

    @Test
    void shouldExecuteJsonPayloadScriptSkillThroughUnifiedRuntime() {
        SkillDefinition definition = definition("web_crawler", "script");
        when(scriptExecutor.runScriptSkill("web_crawler", "{\"url\":\"https://example.com\"}")).thenReturn("抓取完成");

        String result = runtimeService.execute(definition, "{\"url\":\"https://example.com\"}");

        assertThat(result).isEqualTo("抓取完成");
        verify(scriptExecutor).runScriptSkill("web_crawler", "{\"url\":\"https://example.com\"}");
    }

    @Test
    void shouldExecuteBuiltinSkillThroughUnifiedRuntime() {
        SkillDefinition definition = definition("workspace-review", "builtin");
        when(builtinExecutor.executeBySkillId("workspace-review", "审查项目")).thenReturn(Optional.of(
                new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN_SKILL:WORKSPACE_REVIEW",
                        "detail",
                        "审查完成",
                        true
                )
        ));

        String result = runtimeService.execute(definition, "审查项目");

        assertThat(result).isEqualTo("审查完成");
        verify(builtinExecutor).executeBySkillId("workspace-review", "审查项目");
    }

    @Test
    void shouldRejectPromptSkillDirectExecution() {
        SkillDefinition definition = definition("clawhub-summarize", "prompt");

        assertThatThrownBy(() -> runtimeService.execute(definition, "总结"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可直接执行");
    }

    @Test
    void shouldResolveAllowedSkillByIdBeforeExecution() {
        SkillRegistryService registryService = mock(SkillRegistryService.class);
        SkillRuntimeService runtimeService = new SkillRuntimeService(scriptExecutor, builtinExecutor, registryService);
        SkillDefinition definition = definition("repo_inspector", "python");
        when(registryService.listAgentVisibleDefinitions(Set.of("script"))).thenReturn(List.of(definition));
        when(scriptExecutor.runScriptSkillByGoal("repo_inspector", "分析项目")).thenReturn("分析完成");

        String result = runtimeService.executeBySkillId("repo_inspector", "分析项目", Set.of("script"));

        assertThat(result).isEqualTo("分析完成");
        verify(registryService).listAgentVisibleDefinitions(Set.of("script"));
    }

    private SkillDefinition definition(String skillId, String executorType) {
        return new SkillDefinition(
                skillId,
                skillId,
                "desc",
                "SCRIPT",
                "source",
                "",
                List.of(),
                List.of(),
                List.of("script"),
                "simplified",
                "session-only",
                executorType,
                "executor",
                true,
                10,
                true
        );
    }
}
