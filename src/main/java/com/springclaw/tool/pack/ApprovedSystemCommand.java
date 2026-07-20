package com.springclaw.tool.pack;

import java.util.Optional;

/**
 * Closed command grammar shared by planning and the final execution boundary.
 */
public final class ApprovedSystemCommand {

    private static final String UNSAFE_CHARACTERS = ";|&<>$(){}[]";

    private ApprovedSystemCommand() {
    }

    public static Optional<String> normalize(String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            return Optional.empty();
        }

        String command = rawCommand.trim();
        if (containsUnsafeCharacter(command)) {
            return Optional.empty();
        }
        if ("pwd".equals(command) || "git status".equals(command)) {
            return Optional.of(command);
        }
        if (command.startsWith("echo ") && !command.substring("echo ".length()).trim().isEmpty()) {
            return Optional.of(command);
        }
        return Optional.empty();
    }

    public static boolean isApproved(String rawCommand) {
        return normalize(rawCommand).isPresent();
    }

    private static boolean containsUnsafeCharacter(String command) {
        if (command.indexOf('\\') >= 0 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return true;
        }
        return command.chars().anyMatch(character -> UNSAFE_CHARACTERS.indexOf(character) >= 0);
    }
}
