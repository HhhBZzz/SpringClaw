package com.springclaw.service.memory.evaluation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRedlineGatePolicyTest {

    private final Path projectRoot = Path.of("").toAbsolutePath();

    @Test
    void memoryRedlineGateShouldHaveLocalScriptAndCiWorkflowEntryPoint()
            throws IOException {
        Path gateTest = projectRoot.resolve(
                "src/test/java/com/springclaw/service/memory/evaluation/MemoryEffectivenessRedlineGateTest.java"
        );
        Path script = projectRoot.resolve("scripts/check-memory-redline-gate.sh");
        Path workflow = projectRoot.resolve(".github/workflows/memory-redline-gate.yml");

        assertThat(gateTest).isRegularFile();
        assertThat(script).isRegularFile();
        assertThat(workflow).isRegularFile();

        String gateTestSource = Files.readString(gateTest);
        String scriptSource = Files.readString(script);
        String workflowSource = Files.readString(workflow);

        assertThat(gateTestSource)
                .contains("class MemoryEffectivenessRedlineGateTest")
                .contains("MemoryEffectivenessRedlineReportService")
                .contains("assertThat(report.failed()).isZero()")
                .doesNotContain("@Disabled");
        assertThat(scriptSource)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("mvn -q -Dtest=MemoryEffectivenessRedlineGateTest test");
        assertThat(workflowSource)
                .contains("name: Memory Redline Gate")
                .contains("pull_request:")
                .contains("actions/setup-java")
                .contains("java-version: '17'")
                .contains("scripts/check-memory-redline-gate.sh");
    }
}
