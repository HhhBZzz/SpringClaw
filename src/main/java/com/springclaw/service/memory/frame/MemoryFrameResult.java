package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;

import java.util.Objects;

public record MemoryFrameResult(
        MemoryFrame frame,
        MemoryRetrievalTrace trace
) {
    public MemoryFrameResult {
        frame = Objects.requireNonNull(frame, "frame");
        trace = Objects.requireNonNull(trace, "trace");
    }
}
