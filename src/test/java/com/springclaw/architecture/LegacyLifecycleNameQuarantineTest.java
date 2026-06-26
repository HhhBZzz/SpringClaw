package com.springclaw.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyLifecycleNameQuarantineTest {

    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final Path RUNTIME_BRIDGE_PACKAGE =
            Path.of("src/main/java/com/springclaw/runtime/bridge");

    @Test
    void productionWiringDoesNotImportLegacyLifecycleNames() throws IOException {
        List<Path> offenders;
        try (var paths = Files.walk(MAIN_SOURCE)) {
            offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(RUNTIME_BRIDGE_PACKAGE))
                    .filter(LegacyLifecycleNameQuarantineTest::importsLegacyLifecycleName)
                    .toList();
        }

        assertThat(offenders)
                .as("production code outside runtime.bridge must use RunLifecycle* names")
                .isEmpty();
    }

    private static boolean importsLegacyLifecycleName(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains(
                    "import com.springclaw.runtime.bridge.LegacyRuntimeBridge;"
            ) || source.contains(
                    "import com.springclaw.runtime.bridge.LegacyLifecycleObserver;"
            );
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read " + path, ex);
        }
    }
}
