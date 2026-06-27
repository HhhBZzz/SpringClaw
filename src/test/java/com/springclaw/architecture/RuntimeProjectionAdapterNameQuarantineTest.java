package com.springclaw.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProjectionAdapterNameQuarantineTest {

    private static final Path SOURCE_ROOT = Path.of("src");
    private static final Path RUNTIME_BRIDGE_PACKAGE =
            Path.of("src/main/java/com/springclaw/runtime/bridge");

    @Test
    void activeRuntimeProjectionAdaptersDoNotUseLegacyNames() throws IOException {
        for (String sourceFile : deprecatedSourceFiles()) {
            assertThat(RUNTIME_BRIDGE_PACKAGE.resolve(sourceFile))
                    .as("active runtime projection adapters must not keep legacy names")
                    .doesNotExist();
        }

        List<Path> offenders;
        try (var paths = Files.walk(SOURCE_ROOT)) {
            offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(RuntimeProjectionAdapterNameQuarantineTest::mentionsDeprecatedAdapterName)
                    .toList();
        }

        assertThat(offenders)
                .as("runtime projection adapters must use canonical or rollback-specific names")
                .isEmpty();
    }

    private static Set<String> deprecatedSourceFiles() {
        return Set.of(
                legacy("ExecutionDecisionAdapter.java"),
                legacy("RunResultAdapter.java"),
                legacy("RunContextAdapter.java")
        );
    }

    private static boolean mentionsDeprecatedAdapterName(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains(legacy("ExecutionDecisionAdapter"))
                    || source.contains(legacy("RunResultAdapter"))
                    || source.contains(legacy("RunContextAdapter"));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read " + path, ex);
        }
    }

    private static String legacy(String suffix) {
        return "Legacy" + suffix;
    }
}
