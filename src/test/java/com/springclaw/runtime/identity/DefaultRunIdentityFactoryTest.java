package com.springclaw.runtime.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRunIdentityFactoryTest {

    private final DefaultRunIdentityFactory factory = new DefaultRunIdentityFactory();

    @Test
    void createReturnsNormalizedLowercaseHex() {
        assertThat(factory.create()).matches("[0-9a-f]{32}");
    }

    @Test
    void acceptReturnsTheSuppliedNormalizedId() {
        String runId = "0123456789abcdef0123456789abcdef";

        assertThat(factory.accept(runId)).isEqualTo(runId);
    }

    @Test
    void acceptRejectsNullBlankHyphenatedUppercaseAndWrongLengthValues() {
        assertThatThrownBy(() -> factory.accept(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("runId must be exactly 32 lowercase hexadecimal characters");
        assertThatThrownBy(() -> factory.accept(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.accept("01234567-89ab-cdef-0123-456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.accept("ABCDEF0123456789ABCDEF0123456789"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.accept("0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
