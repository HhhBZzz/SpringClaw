package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltinSkillExecutionServiceTest {

    @Test
    void shouldDispatchBuiltinSkillThroughRegisteredHandler() {
        SkillRegistryService registryService = mock(SkillRegistryService.class);
        SkillDefinition definition = definition("custom-builtin");
        when(registryService.listAllDefinitions()).thenReturn(List.of(definition));
        BuiltinSkillHandler handler = new BuiltinSkillHandler() {
            @Override
            public String skillId() {
                return "custom-builtin";
            }

            @Override
            public Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
                return Optional.of(new LocalSkillFallbackService.LocalSkillResult(
                        "BUILTIN_SKILL:CUSTOM",
                        "detail",
                        "handled:" + question,
                        true
                ));
            }
        };
        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(registryService, List.of(handler));

        Optional<LocalSkillFallbackService.LocalSkillResult> result = service.executeBySkillId("custom-builtin", "hello");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().fallbackAnswer()).isEqualTo("handled:hello");
    }

    @Test
    void shouldReturnEmptyForUnregisteredBuiltinHandler() {
        SkillRegistryService registryService = mock(SkillRegistryService.class);
        when(registryService.listAllDefinitions()).thenReturn(List.of(definition("missing-builtin")));
        BuiltinSkillExecutionService service = new BuiltinSkillExecutionService(registryService, List.of());

        Optional<LocalSkillFallbackService.LocalSkillResult> result = service.executeBySkillId("missing-builtin", "hello");

        assertThat(result).isEmpty();
    }

    private SkillDefinition definition(String skillId) {
        return new SkillDefinition(
                skillId,
                skillId,
                "desc",
                "BUILTIN",
                "source",
                "",
                List.of(skillId),
                List.of(),
                List.of("workspace"),
                "simplified",
                "session-only",
                "builtin",
                "executor",
                true,
                10,
                true
        );
    }
}
