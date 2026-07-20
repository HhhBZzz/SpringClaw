package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 系统工具包。
 */
@Component
@ToolPackDescriptor(
    id = "system",
    toolset = "system",
    triggerKeywords = {"当前时间", "现在几点", "uuid", "jvm", "启动参数", "当前模型", "provider", "切换模型", "模型状态"},
    fallbackCandidate = true,
    riskLevel = "read",
    description = "系统时间、UUID、JVM 信息和受控命令执行"
)
public class SystemToolPack {

    private final boolean commandEnabled;
    private final String commandMode;
    private final Set<String> allowedCommands;
    private final Set<String> blockedCommands;
    private final boolean allowAllCommands;
    private final int commandTimeoutSeconds;
    private final int maxOutputChars;

    public SystemToolPack(boolean commandEnabled,
                          String allowedCommands,
                          int commandTimeoutSeconds,
                          int maxOutputChars) {
        this(commandEnabled, "whitelist", allowedCommands, "", commandTimeoutSeconds, maxOutputChars);
    }

    @Autowired
    public SystemToolPack(@Value("${springclaw.tools.system.command-enabled:false}") boolean commandEnabled,
                          @Value("${springclaw.tools.system.command-mode:whitelist}") String commandMode,
                          @Value("${springclaw.tools.system.allowed-commands:pwd,ls,date,whoami,uname,id}") String allowedCommands,
                          @Value("${springclaw.tools.system.blocked-commands:rm,dd,shutdown,reboot,halt,poweroff,mkfs,fdisk,kill,killall,pkill}") String blockedCommands,
                          @Value("${springclaw.tools.system.command-timeout-seconds:5}") int commandTimeoutSeconds,
                          @Value("${springclaw.tools.system.max-output-chars:2000}") int maxOutputChars) {
        this.commandEnabled = commandEnabled;
        this.commandMode = StringUtils.hasText(commandMode) ? commandMode.trim().toLowerCase(Locale.ROOT) : "whitelist";
        this.allowedCommands = Arrays.stream(allowedCommands.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        this.blockedCommands = Arrays.stream(blockedCommands.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        this.allowAllCommands = this.allowedCommands.contains("*");
        this.commandTimeoutSeconds = Math.max(1, commandTimeoutSeconds);
        this.maxOutputChars = Math.max(512, maxOutputChars);
    }

    @Tool(description = "获取当前系统时间（ISO-8601格式）")
    public String now() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @Tool(description = "生成一个UUID")
    public String uuid() {
        return UUID.randomUUID().toString();
    }

    @Tool(description = "获取当前JVM运行信息")
    public String jvmInfo() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMb = runtime.maxMemory() / 1024 / 1024;
        return "JVM=" + ManagementFactory.getRuntimeMXBean().getVmName()
                + ", uptimeMs=" + ManagementFactory.getRuntimeMXBean().getUptime()
                + ", memoryUsedMB=" + usedMb
                + ", memoryMaxMB=" + maxMb;
    }

    @Tool(description = "获取当前 Java 进程的 JVM 启动参数（-Xmx、-D 等）")
    public String jvmInputArguments() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (args == null || args.isEmpty()) {
            return "未读取到 JVM 启动参数";
        }
        String joined = String.join("\n", args);
        if (joined.length() > maxOutputChars) {
            return joined.substring(0, maxOutputChars) + "\n...<TRUNCATED>";
        }
        return joined;
    }

    @Tool(name = "systemRunCommand", description = "执行受控系统命令（仅白名单命令，适用于查看时间、路径、系统信息等只读操作）")
    public String runCommand(String commandLine) {
        if (!commandEnabled) {
            return "命令执行能力未开启（springclaw.tools.system.command-enabled=false）";
        }
        if (!StringUtils.hasText(commandLine)) {
            throw new BusinessException(40061, "命令不能为空");
        }

        String approvedCommand = ApprovedSystemCommand.normalize(commandLine)
                .orElseThrow(() -> new BusinessException(40062,
                        "仅允许执行 echo <text>、pwd 或 git status"));
        String[] parts = approvedCommand.split("\\s+");
        String command = parts[0].toLowerCase();
        if (!isCommandAllowed(command)) {
            if (isBlacklistMode()) {
                return "命令在黑名单内: " + command + "，禁止: " + String.join(",", blockedCommands);
            }
            return "命令不在白名单内: " + command + "，允许: " + String.join(",", allowedCommands);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(parts);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时（" + commandTimeoutSeconds + "s）";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (output.length() > maxOutputChars) {
                output = output.substring(0, maxOutputChars) + "\n...<TRUNCATED>";
            }
            return "exitCode=" + process.exitValue() + "\n" + output;
        } catch (IOException ex) {
            throw new BusinessException(50031, "命令执行失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50032, "命令执行被中断");
        }
    }

    private boolean isBlacklistMode() {
        return "blacklist".equals(commandMode);
    }

    private boolean isCommandAllowed(String command) {
        if (isBlacklistMode()) {
            return !blockedCommands.contains(command);
        }
        return allowAllCommands || allowedCommands.contains(command);
    }
}
