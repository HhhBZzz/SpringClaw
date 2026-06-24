package com.springclaw.runtime.bridge;

import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;

import java.util.Objects;

public record LegacyContextView(
        AssembledContext assembled,
        ContextInjection injection
) {
    public LegacyContextView {
        assembled = Objects.requireNonNull(assembled, "assembled");
        injection = injection == null ? ContextInjection.empty() : injection;
    }
}
