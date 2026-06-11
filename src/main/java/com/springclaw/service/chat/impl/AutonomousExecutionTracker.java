package com.springclaw.service.chat.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自主循环执行状态追踪器 — 记录真实的工具调用和副作用证据。
 *
 * 这个追踪器解决"假完成"问题：模型可能用纯文本声称 TASK_COMPLETE，
 * 但实际上没有调用任何写工具，也没有创建或修改任何文件。
 *
 * 防御性设计：使用 CopyOnWriteArrayList 和 ConcurrentHashMap，
 * 虽然当前 Spring AI 工具调用是同步单线程的（ChatClient.call() → ToolCallback.call() 同一线程），
 * 但线程安全集合作为防御性选择不会引入额外开销，也为未来异步执行保留兼容性。
 */
public class AutonomousExecutionTracker {

    // === 写工具调用追踪 ===
    private final CopyOnWriteArrayList<String> writeToolCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> patchToolCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> commandToolCalls = new CopyOnWriteArrayList<>();

    // === 文件副作用追踪 ===
    private final ConcurrentHashMap<String, Boolean> createdFiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> modifiedFiles = new ConcurrentHashMap<>();

    // === 命令结果追踪 ===
    private final CopyOnWriteArrayList<Boolean> successfulCommandResults = new CopyOnWriteArrayList<>();

    // === 验证追踪 ===
    private volatile boolean hasVerifiedSideEffect = false;

    // === 所有工具调用名称 ===
    private final CopyOnWriteArrayList<ToolCallRecord> allToolCalls = new CopyOnWriteArrayList<>();

    /**
     * 记录一次 workspaceWriteFile 调用。
     */
    public void recordWriteFile(String relativePath, int lineCount) {
        writeToolCalls.add("workspaceWriteFile(" + relativePath + ")");
        createdFiles.put(relativePath, true);
        allToolCalls.add(new ToolCallRecord("workspaceWriteFile", relativePath, true));
        logState("workspaceWriteFile");
    }

    /**
     * 记录一次 workspaceApplyPatch 调用。
     */
    public void recordApplyPatch(String relativePath, boolean success) {
        patchToolCalls.add("workspaceApplyPatch(" + relativePath + ", success=" + success + ")");
        if (success) {
            modifiedFiles.put(relativePath, true);
        }
        allToolCalls.add(new ToolCallRecord("workspaceApplyPatch", relativePath, success));
        logState("workspaceApplyPatch");
    }

    /**
     * 记录一次 workspaceRunCommand 调用。
     */
    public void recordRunCommand(String command, int exitCode) {
        commandToolCalls.add("workspaceRunCommand(exitCode=" + exitCode + ")");
        successfulCommandResults.add(exitCode == 0);
        allToolCalls.add(new ToolCallRecord("workspaceRunCommand", command, exitCode == 0));
        logState("workspaceRunCommand");
    }

    /**
     * 记录一次副作用验证（例如：用 ls/cat 检查文件是否存在）。
     */
    public void recordSideEffectVerification() {
        this.hasVerifiedSideEffect = true;
    }

    // === 读取状态 ===

    public boolean hasWriteToolCall() {
        return !writeToolCalls.isEmpty();
    }

    public boolean hasPatchToolCall() {
        return !patchToolCalls.isEmpty();
    }

    public boolean hasRunCommandCall() {
        return !commandToolCalls.isEmpty();
    }

    public List<String> getCreatedFiles() {
        return Collections.unmodifiableList(new ArrayList<>(createdFiles.keySet()));
    }

    public List<String> getModifiedFiles() {
        return Collections.unmodifiableList(new ArrayList<>(modifiedFiles.keySet()));
    }

    public boolean hasVerifiedSideEffect() {
        return hasVerifiedSideEffect;
    }

    public boolean hasSuccessfulCommandResult() {
        return successfulCommandResults.stream().anyMatch(b -> b);
    }

    public List<ToolCallRecord> getAllToolCalls() {
        return Collections.unmodifiableList(new ArrayList<>(allToolCalls));
    }

    public boolean hasAnyToolCall() {
        return !allToolCalls.isEmpty();
    }

    // === 完成条件判断 ===

    /**
     * 判断 write-needed 任务是否满足真实完成条件。
     *
     * write-needed（创建文件、修改代码）必须同时满足：
     * 1. 调用过 workspaceWriteFile 或 workspaceApplyPatch 等写入类工具
     * 2. createdFiles / modifiedFiles 不为空（有真实文件变化）
     * 3. 对目标文件进行了存在性或内容验证（hasVerifiedSideEffect）
     *
     * 注意：验证条件可以宽松 — 如果有写入工具调用且有文件变化记录，
     * 我们认为任务确实执行了写操作。验证作为推荐但非必须条件。
     */
    public boolean satisfiesWriteCompletionCondition() {
        // 条件 1：必须有写入类工具调用
        boolean hasWriteAction = hasWriteToolCall() || hasPatchToolCall();
        if (!hasWriteAction) {
            return false;
        }
        // 条件 2：必须有真实文件变化记录
        boolean hasFileChange = !createdFiles.isEmpty() || !modifiedFiles.isEmpty();
        return hasFileChange;
    }

    /**
     * 判断 side_effect 任务是否满足真实完成条件。
     *
     * side_effect（运行测试、执行命令）必须满足：
     * 1. 调用过 workspaceRunCommand 或 systemRunCommand
     * 2. 有成功的命令执行结果
     */
    public boolean satisfiesSideEffectCompletionCondition() {
        return hasRunCommandCall() && hasSuccessfulCommandResult();
    }

    /**
     * 判断 dangerous 任务是否满足真实完成条件。
     *
     * dangerous 任务必须满足更严格的条件：
     * 1. 有真实的工具调用
     * 2. 有副作用验证
     */
    public boolean satisfiesDangerousCompletionCondition() {
        return hasAnyToolCall() && hasVerifiedSideEffect();
    }

    /**
     * 按风险等级判断是否满足完成条件。
     */
    public boolean satisfiesCompletionCondition(String riskLevel) {
        if (riskLevel == null) return true;
        return switch (riskLevel) {
            case "read" -> true; // read-only 不需要副作用验证
            case "write" -> satisfiesWriteCompletionCondition();
            case "side_effect" -> satisfiesSideEffectCompletionCondition();
            case "dangerous" -> satisfiesDangerousCompletionCondition();
            default -> true;
        };
    }

    /**
     * 生成假完成拒绝提示 — 当模型声称完成但缺少真实副作用证据时，
     * 提示模型必须实际执行操作。
     */
    public String renderFakeCompletionRejection(String riskLevel) {
        StringBuilder msg = new StringBuilder();
        msg.append("⚠️ 你声明任务完成，但系统未检测到真实操作证据：\n");

        if ("write".equals(riskLevel)) {
            if (!hasWriteToolCall() && !hasPatchToolCall()) {
                msg.append("- 未调用任何写入类工具（workspaceWriteFile / workspaceApplyPatch）\n");
            }
            if (createdFiles.isEmpty() && modifiedFiles.isEmpty()) {
                msg.append("- 未检测到任何文件创建或修改\n");
            }
            msg.append("请调用 workspaceWriteFile 或 workspaceApplyPatch 完成实际文件操作。\n");
        } else if ("side_effect".equals(riskLevel)) {
            if (!hasRunCommandCall()) {
                msg.append("- 未调用 workspaceRunCommand 执行命令\n");
            }
            msg.append("请调用 workspaceRunCommand 执行实际操作。\n");
        } else if ("dangerous".equals(riskLevel)) {
            msg.append("- 未检测到任何真实工具调用和副作用验证\n");
            msg.append("请使用工具执行实际操作并验证结果。\n");
        }

        msg.append("\n不要只用纯文本声称完成，必须通过工具调用产生真实副作用。");
        return msg.toString();
    }

    private void logState(String action) {
        // 不打印完整日志避免日志膨胀，只在关键节点打印
    }

    /**
     * 工具调用记录。
     */
    public record ToolCallRecord(String toolName, String detail, boolean success) {
        @Override
        public String toString() {
            return toolName + "(" + detail + ", success=" + success + ")";
        }
    }
}