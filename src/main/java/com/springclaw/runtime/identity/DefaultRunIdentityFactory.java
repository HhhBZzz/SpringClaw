package com.springclaw.runtime.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class DefaultRunIdentityFactory implements RunIdentityFactory {

    private static final Pattern NORMALIZED_ID = Pattern.compile("[0-9a-f]{32}");

    @Override
    public String create() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public String accept(String suppliedRunId) {
        if (suppliedRunId == null || !NORMALIZED_ID.matcher(suppliedRunId).matches()) {
            throw new IllegalArgumentException(
                    "runId must be exactly 32 lowercase hexadecimal characters"
            );
        }
        return suppliedRunId;
    }
}
