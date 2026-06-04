package com.springclaw.tool.runtime;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Default provider implementation for existing ToolPack beans.
 */
public class DefaultAgentToolProvider implements AgentToolProvider {

    private final String id;
    private final Set<String> requiredToolPacks;
    private final Object tool;
    private final Predicate<String> matcher;
    private final boolean includeForAgentMode;

    public DefaultAgentToolProvider(String id,
                                    Set<String> requiredToolPacks,
                                    Object tool,
                                    Predicate<String> matcher) {
        this(id, requiredToolPacks, tool, matcher, true);
    }

    public DefaultAgentToolProvider(String id,
                                    Set<String> requiredToolPacks,
                                    Object tool,
                                    Predicate<String> matcher,
                                    boolean includeForAgentMode) {
        this.id = id;
        this.requiredToolPacks = requiredToolPacks == null ? Set.of() : Set.copyOf(requiredToolPacks);
        this.tool = tool;
        this.matcher = matcher == null ? text -> false : matcher;
        this.includeForAgentMode = includeForAgentMode;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<String> requiredToolPacks() {
        return requiredToolPacks;
    }

    @Override
    public Object tool() {
        return tool;
    }

    @Override
    public boolean matches(String text) {
        return matcher.test(text == null ? "" : text);
    }

    @Override
    public boolean includeForAgentMode() {
        return includeForAgentMode;
    }
}
