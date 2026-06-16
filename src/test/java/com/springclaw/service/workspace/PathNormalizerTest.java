package com.springclaw.service.workspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathNormalizerTest {

    private final Path root = Path.of("/tmp/repo").toAbsolutePath();
    private final PathNormalizer normalizer = new PathNormalizer();

    @Test
    void normalizesDotPrefix() {
        assertThat(normalizer.normalizeRepoPath(root, "./src/A.java")).isEqualTo("src/A.java");
    }

    @Test
    void normalizesAbsoluteInsideRoot() {
        assertThat(normalizer.normalizeRepoPath(root, root.resolve("src/B.java").toString()))
                .isEqualTo("src/B.java");
    }

    @Test
    void normalizesNestedDotDot() {
        assertThat(normalizer.normalizeRepoPath(root, "src/foo/../A.java")).isEqualTo("src/A.java");
    }

    @Test
    void normalizesWindowsBackslash() {
        assertThat(normalizer.normalizeRepoPath(root, "src\\foo\\A.java")).isEqualTo("src/foo/A.java");
    }

    @Test
    void rejectsEscapeAttempts() {
        assertThatThrownBy(() -> normalizer.normalizeRepoPath(root, "../../etc/passwd"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("path escapes workspace");
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> normalizer.normalizeRepoPath(root, ""))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> normalizer.normalizeRepoPath(root, null))
                .isInstanceOf(SecurityException.class);
    }
}
