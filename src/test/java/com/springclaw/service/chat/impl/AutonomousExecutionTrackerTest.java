package com.springclaw.service.chat.impl;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutonomousExecutionTracker 和假完成防护机制的测试。
 *
 * 核心测试场景：
 * 1. read-only 任务：纯文本 TASK_COMPLETE 可以直接完成
 * 2. write-needed 假完成：模型声称 TASK_COMPLETE 但没有写工具调用 → 不能完成
 * 3. write-needed 真实完成：模型调用 workspaceWriteFile + TASK_COMPLETE → 可以完成
 * 4. 修改文件假完成：模型声称 TASK_COMPLETE 但没有 patch/write 工具调用 → 不能完成
 * 5. command-needed 假完成：模型声称 TASK_COMPLETE 但没有 runCommand → 不能完成
 * 6. early stop 限制：write-needed 禁止纯文本早停
 */
class AutonomousExecutionTrackerTest {

    @Nested
    @DisplayName("read-only 任务完成条件")
    class ReadOnlyTests {

        @Test
        @DisplayName("read-only：satisfiesCompletionCondition 直接返回 true")
        void readOnlyCompletionAllowed() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            // read-only 不需要任何副作用验证
            assertTrue(tracker.satisfiesCompletionCondition("read"));
            assertTrue(tracker.satisfiesCompletionCondition(null));
        }

        @Test
        @DisplayName("read-only：没有工具调用也可以完成")
        void readOnlyNoToolCallStillAllowed() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            assertFalse(tracker.hasAnyToolCall());
            assertTrue(tracker.satisfiesCompletionCondition("read"));
        }
    }

    @Nested
    @DisplayName("write-needed 任务假完成拦截")
    class WriteFakeCompletionTests {

        @Test
        @DisplayName("write-needed 假完成：没有写工具调用 → 不能完成")
        void writeFakeCompletionNoWriteToolCall() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            // tracker 是空的 — 模型只输出 TASK_COMPLETE 但没有调用任何写工具
            assertFalse(tracker.satisfiesCompletionCondition("write"));
            assertFalse(tracker.hasWriteToolCall());
            assertFalse(tracker.hasPatchToolCall());
            assertTrue(tracker.getCreatedFiles().isEmpty());
            assertTrue(tracker.getModifiedFiles().isEmpty());
        }

        @Test
        @DisplayName("write-needed 假完成：只有 runCommand 不算写操作")
        void writeFakeCompletionOnlyCommand() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordRunCommand("ls", 0);
            // runCommand 是命令执行，不是写文件
            assertFalse(tracker.hasWriteToolCall());
            assertFalse(tracker.hasPatchToolCall());
            assertTrue(tracker.getCreatedFiles().isEmpty());
            assertTrue(tracker.getModifiedFiles().isEmpty());
            assertFalse(tracker.satisfiesCompletionCondition("write"));
        }

        @Test
        @DisplayName("write-needed 假完成：renderFakeCompletionRejection 正确提示")
        void writeFakeCompletionRejectionMessage() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            String rejection = tracker.renderFakeCompletionRejection("write");
            assertTrue(rejection.contains("workspaceWriteFile"));
            assertTrue(rejection.contains("workspaceApplyPatch"));
            assertTrue(rejection.contains("未调用"));
            assertTrue(rejection.contains("未检测到"));
        }
    }

    @Nested
    @DisplayName("write-needed 任务真实完成")
    class WriteRealCompletionTests {

        @Test
        @DisplayName("write-needed 真实完成：workspaceWriteFile + 文件创建 → 可以完成")
        void writeRealCompletionWithWriteFile() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordWriteFile("README_CN.md", 50);
            assertTrue(tracker.hasWriteToolCall());
            assertFalse(tracker.getCreatedFiles().isEmpty());
            assertTrue(tracker.getCreatedFiles().contains("README_CN.md"));
            assertTrue(tracker.satisfiesCompletionCondition("write"));
        }

        @Test
        @DisplayName("write-needed 真实完成：workspaceApplyPatch + 文件修改 → 可以完成")
        void writeRealCompletionWithPatch() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordApplyPatch("README.md", true);
            assertTrue(tracker.hasPatchToolCall());
            assertFalse(tracker.getModifiedFiles().isEmpty());
            assertTrue(tracker.getModifiedFiles().contains("README.md"));
            assertTrue(tracker.satisfiesCompletionCondition("write"));
        }

        @Test
        @DisplayName("write-needed 真实完成：writeFile + runCommand 验证")
        void writeRealCompletionWithWriteAndVerify() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordWriteFile("README_CN.md", 50);
            tracker.recordRunCommand("cat README_CN.md", 0);
            tracker.recordSideEffectVerification();
            assertTrue(tracker.hasWriteToolCall());
            assertTrue(tracker.hasRunCommandCall());
            assertTrue(tracker.hasVerifiedSideEffect());
            assertTrue(tracker.satisfiesCompletionCondition("write"));
        }
    }

    @Nested
    @DisplayName("side_effect 任务完成条件")
    class SideEffectTests {

        @Test
        @DisplayName("side_effect 假完成：没有 runCommand → 不能完成")
        void sideEffectFakeCompletionNoCommand() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            assertFalse(tracker.satisfiesCompletionCondition("side_effect"));
        }

        @Test
        @DisplayName("side_effect 假完成：runCommand 失败（exitCode!=0）→ 不能完成")
        void sideEffectFakeCompletionCommandFailed() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordRunCommand("pytest", 1);
            assertTrue(tracker.hasRunCommandCall());
            assertFalse(tracker.hasSuccessfulCommandResult());
            assertFalse(tracker.satisfiesCompletionCondition("side_effect"));
        }

        @Test
        @DisplayName("side_effect 真实完成：runCommand 成功 → 可以完成")
        void sideEffectRealCompletionCommandSucceeded() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordRunCommand("pytest", 0);
            assertTrue(tracker.hasRunCommandCall());
            assertTrue(tracker.hasSuccessfulCommandResult());
            assertTrue(tracker.satisfiesCompletionCondition("side_effect"));
        }
    }

    @Nested
    @DisplayName("dangerous 任务完成条件")
    class DangerousTests {

        @Test
        @DisplayName("dangerous 假完成：没有工具调用 → 不能完成")
        void dangerousFakeCompletionNoToolCall() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            assertFalse(tracker.satisfiesCompletionCondition("dangerous"));
        }

        @Test
        @DisplayName("dangerous 真实完成：有工具调用 + 有副作用验证 → 可以完成")
        void dangerousRealCompletion() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordWriteFile("config.yaml", 10);
            tracker.recordSideEffectVerification();
            assertTrue(tracker.hasAnyToolCall());
            assertTrue(tracker.hasVerifiedSideEffect());
            assertTrue(tracker.satisfiesCompletionCondition("dangerous"));
        }

        @Test
        @DisplayName("dangerous 假完成：有工具调用但没有副作用验证 → 不能完成")
        void dangerousFakeCompletionNoVerification() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordWriteFile("config.yaml", 10);
            assertTrue(tracker.hasAnyToolCall());
            assertFalse(tracker.hasVerifiedSideEffect());
            assertFalse(tracker.satisfiesCompletionCondition("dangerous"));
        }
    }

    @Nested
    @DisplayName("修改文件假完成场景")
    class ModifyFileFakeCompletionTests {

        @Test
        @DisplayName("修改文件假完成：只有 writeFile 创建新文件不算修改")
        void modifyFileFakeCompletionOnlyWriteNotPatch() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordWriteFile("NEW_FILE.md", 10);
            // 写入了文件，但没有 patch 调用
            // 这算 write-needed 完成条件（因为 createdFiles 不为空）
            assertTrue(tracker.satisfiesCompletionCondition("write"));
        }

        @Test
        @DisplayName("修改文件真实完成：applyPatch 修改文件 → 可以完成")
        void modifyFileRealCompletionWithPatch() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordApplyPatch("README.md", true);
            assertTrue(tracker.hasPatchToolCall());
            assertTrue(tracker.getModifiedFiles().contains("README.md"));
            assertTrue(tracker.satisfiesCompletionCondition("write"));
        }

        @Test
        @DisplayName("applyPatch 失败：不算真实修改")
        void modifyFilePatchFailed() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordApplyPatch("README.md", false);
            assertTrue(tracker.hasPatchToolCall());
            // 失败的 patch 不记录在 modifiedFiles
            assertTrue(tracker.getModifiedFiles().isEmpty());
            assertFalse(tracker.satisfiesCompletionCondition("write"));
        }
    }

    @Nested
    @DisplayName("追踪器状态一致性")
    class TrackerConsistencyTests {

        @Test
        @DisplayName("多次工具调用累积记录")
        void multipleToolCallsAccumulate() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordWriteFile("a.txt", 5);
            tracker.recordWriteFile("b.txt", 10);
            tracker.recordApplyPatch("c.java", true);
            tracker.recordRunCommand("mvn test", 0);
            assertEquals(2, tracker.getCreatedFiles().size());
            assertEquals(1, tracker.getModifiedFiles().size());
            assertTrue(tracker.hasWriteToolCall());
            assertTrue(tracker.hasPatchToolCall());
            assertTrue(tracker.hasRunCommandCall());
            assertTrue(tracker.hasAnyToolCall());
            assertEquals(4, tracker.getAllToolCalls().size());
        }

        @Test
        @DisplayName("ToolCallRecord 包含成功/失败状态")
        void toolCallRecordHasSuccessStatus() {
            AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
            tracker.recordApplyPatch("a.java", true);
            tracker.recordApplyPatch("b.java", false);
            tracker.recordRunCommand("ls", 0);
            tracker.recordRunCommand("bad_cmd", 1);

            List<AutonomousExecutionTracker.ToolCallRecord> calls = tracker.getAllToolCalls();
            assertEquals(4, calls.size());
            assertTrue(calls.get(0).success());
            assertFalse(calls.get(1).success());
            assertTrue(calls.get(2).success());
            assertFalse(calls.get(3).success());
        }
    }
}