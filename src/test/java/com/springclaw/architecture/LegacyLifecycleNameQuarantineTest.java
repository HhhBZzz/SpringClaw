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

    @Test
    void deprecatedLegacyLifecycleShimSourcesAreRemoved() {
        assertThat(RUNTIME_BRIDGE_PACKAGE.resolve(legacy("RuntimeBridge.java")))
                .as("deprecated empty compatibility interface must be removed")
                .doesNotExist();
        assertThat(RUNTIME_BRIDGE_PACKAGE.resolve("Default" + legacy("RuntimeBridge.java")))
                .as("deprecated delegating compatibility bridge must be removed")
                .doesNotExist();
        assertThat(RUNTIME_BRIDGE_PACKAGE.resolve(legacy("LifecycleObserver.java")))
                .as("deprecated delegating compatibility observer must be removed")
                .doesNotExist();
    }

    private static boolean importsLegacyLifecycleName(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains(
                    "import com.springclaw.runtime.bridge." + legacy("RuntimeBridge") + ";"
            ) || source.contains(
                    "import com.springclaw.runtime.bridge." + legacy("LifecycleObserver") + ";"
            );
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read " + path, ex);
        }
    }

    private static String legacy(String suffix) {
        return "Legacy" + suffix;
    }
}
