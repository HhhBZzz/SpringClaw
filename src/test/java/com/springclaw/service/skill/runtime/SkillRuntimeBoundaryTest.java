package com.springclaw.service.skill.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRuntimeBoundaryTest {

    @Test
    void shouldPreventApplicationCallersFromBypassingSkillRuntime() throws Exception {
        Path sourceRoot = Path.of("src/main/java").toAbsolutePath().normalize();
        List<String> offenders;
        try (var stream = Files.walk(sourceRoot)) {
            offenders = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("ScriptSkillExecutorService.java"))
                    .filter(path -> !path.endsWith("SkillRuntimeService.java"))
                    .filter(path -> !path.endsWith("BuiltinSkillExecutionService.java"))
                    .filter(this::containsDirectScriptExecution)
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    private boolean containsDirectScriptExecution(Path path) {
        try {
            String text = Files.readString(path);
            return text.contains(".runScriptSkill(")
                    || text.contains(".runScriptSkillByGoal(");
        } catch (Exception ex) {
            return false;
        }
    }
}
