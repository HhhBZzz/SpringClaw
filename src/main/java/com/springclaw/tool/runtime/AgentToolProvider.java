package com.springclaw.tool.runtime;

import java.util.Set;

/**
 * Registry item that exposes one Spring AI tool bean to the agent runtime.
 */
public interface AgentToolProvider {

    String id();

    Set<String> requiredToolPacks();

    Object tool();

    boolean matches(String text);

    default boolean includeForAgentMode() {
        return true;
    }

    default boolean isAllowed(Set<String> allowedToolPacks) {
        if (requiredToolPacks() == null || requiredToolPacks().isEmpty()) {
            return true;
        }
        if (allowedToolPacks == null || allowedToolPacks.isEmpty()) {
            return false;
        }
        return requiredToolPacks().stream().anyMatch(allowedToolPacks::contains);
    }
}
