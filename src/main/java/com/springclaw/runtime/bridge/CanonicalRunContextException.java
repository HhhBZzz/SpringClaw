package com.springclaw.runtime.bridge;

import java.util.Objects;

public final class CanonicalRunContextException extends RuntimeException {

    public enum Code {
        ACCEPTED_REQUEST_MISMATCH,
        RUN_ALREADY_TERMINAL,
        CANONICAL_SNAPSHOT_INVARIANT,
        CANONICAL_PROVIDER_MISMATCH
    }

    private final Code code;

    public CanonicalRunContextException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public CanonicalRunContextException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
