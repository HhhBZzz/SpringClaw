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
        // 仅做 unsafe 字符护栏；具体命令白名单由 SystemToolPack.allowed-commands / 执行边界负责，
        // 避免硬编码命令列表架空配置的白名单（原实现只认 pwd/git status/echo，导致 allowed-commands 失效）。
        return Optional.of(command);
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
