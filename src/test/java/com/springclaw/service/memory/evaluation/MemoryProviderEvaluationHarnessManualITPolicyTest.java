package com.springclaw.service.memory.evaluation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryProviderEvaluationHarnessManualITPolicyTest {

    private final Path projectRoot =
            Path.of(System.getProperty("project.root", ""))
                    .toAbsolutePath()
                    .normalize();

    @Test
    void manualProviderHarnessItMustBeExplicitlyGated() throws IOException {
        String source = Files.readString(projectRoot.resolve(
                "src/test/java/com/springclaw/service/memory/evaluation/MemoryProviderEvaluationHarnessManualIT.java"
        ));

        assertThat(source)
                .contains("@EnabledIfEnvironmentVariable")
                .contains("SPRINGCLAW_MEMORY_PROVIDER_EVALUATION_IT")
                .contains("springclaw.memory.evaluation.provider-harness-enabled=true")
                .contains("MemoryProviderEvaluationHarnessService")
                .contains("assertThat(report.failed()).isZero()")
                .contains("assertThat(report.skipped()).isZero()");
    }
}
