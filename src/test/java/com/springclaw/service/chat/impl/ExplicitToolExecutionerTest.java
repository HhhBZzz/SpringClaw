package com.springclaw.service.chat.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExplicitToolExecutioner 单测——锁定手动循环主路径的不变量(从 ReActEngineTest 搬来,改为直接测共享类 public 入口)。
 * <p>覆盖:命名/位置参数解析、markdown bold 行首容错、glob 通配符与 shell 反引号参数原样保留、
 * 未知工具优雅降级、hasActionLine 判定、describeAction 投影。逻辑与原 ReAct 手动循环一致,
 * 只是入口从 {@code engine.execute(完整循环)} 改为 {@code exec.execute(单步 Action 文本)}。</p>
 */
class ExplicitToolExecutionerTest {

    private final ExplicitToolExecutioner exec = new ExplicitToolExecutioner();

    // === execute:Action 解析 + 反射手动执行 ===

    /**
     * 命名参数 Action: search(query="q") → 反射手动执行 search,Observation 含入参值。
     */
    @Test
    void executesNamedArgActionFromText() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 需要搜索相关资料\nAction: search(query=\"q\")";

        assertThat(exec.hasActionLine(thought)).isTrue();
        String observation = exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.searchCalls.get())
                .as("手动循环应反射执行 search 工具恰好 1 次")
                .isEqualTo(1);
        assertThat(observation).contains("q");
    }

    /**
     * 位置参数 Action: search("Spring AI") → 按序绑定到形参 query,手动执行。
     */
    @Test
    void executesPositionalArgActionFromText() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 搜索\nAction: search(\"Spring AI\")";

        String observation = exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.searchCalls.get()).isEqualTo(1);
        assertThat(observation).contains("Spring AI");
    }

    /**
     * Markdown bold + 列表前缀噪声: - **Action:** search(query="q"). → 行首归一化后命中 Action 前缀。
     */
    @Test
    void recognizesMarkdownBoldActionLine() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 需要搜索\n- **Action:** search(query=\"q\").";

        assertThat(exec.hasActionLine(thought)).isTrue();
        exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.searchCalls.get())
                .as("markdown 修饰的 **Action:** 行应被识别并执行")
                .isEqualTo(1);
    }

    /**
     * 回归:glob 通配符参数 {@code pattern="*.*"} 不得被 markdown 归一化损坏为 {@code .}。
     * 参数从原始行取,通配符原样到达工具。
     */
    @Test
    void preservesGlobWildcardArg() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 列出当前目录文件\nAction: glob(pattern=\"*.*\")";

        exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.lastGlobPattern.get())
                .as("glob 通配符参数 *.* 不应被 markdown 归一化损坏为 .")
                .isEqualTo("*.*");
    }

    /**
     * 回归:shell 反引号参数 {@code cmd="echo `pwd`"} 不得被归一化剥成 {@code echo pwd}。
     */
    @Test
    void preservesShellBacktickArg() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 取当前工作目录\nAction: shell(cmd=\"echo `pwd`\")";

        exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.lastShellCmd.get())
                .as("shell 反引号参数 echo `pwd` 不应被剥成 echo pwd")
                .isEqualTo("echo `pwd`");
    }

    /**
     * 回归补充:markdown bold 前缀({@code **Action:**})下,glob 参数 {@code *.*} 仍原样保留——
     * 校验"行首归一化检测 + 参数 raw 提取"两条路径并存。
     */
    @Test
    void preservesGlobArgUnderMarkdownBoldPrefix() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 搜索文件\n- **Action:** glob(pattern=\"*.*\").";

        exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.lastGlobPattern.get())
                .as("**Action:** 前缀下 glob 参数 *.* 也应原样保留")
                .isEqualTo("*.*");
    }

    /**
     * 未知工具优雅降级:Action 调用不存在的工具名 → 返回错误说明 Observation(不抛异常),
     * 供调用方(ReAct/Plan-Execute 循环)回灌给 LLM 纠错。
     */
    @Test
    void unknownToolReturnsErrorObservation() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 调用不存在的工具\nAction: nonexistentTool(foo=\"bar\")";

        String observation = exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(observation).contains("未找到工具", "nonexistentTool");
        assertThat(tool.searchCalls.get()).isEqualTo(0);
    }

    /**
     * 无 Action 行时 execute 返回解析失败说明(不抛),调用方据此判定本轮为最终答案。
     */
    @Test
    void executeReturnsFailureObservationWhenNoActionLine() {
        String thought = "最终答案: 直接回答,无工具调用。";

        String observation = exec.execute(thought, new Object[]{new ExecFixtureTool()}, "req-test");

        assertThat(observation).contains("Action 行解析失败");
    }

    /**
     * 无参工具 Action: bare() → 零参反射调用,返回工具结果。
     */
    @Test
    void executesZeroArgAction() {
        ExecFixtureTool tool = new ExecFixtureTool();
        String thought = "Thought: 调无参工具\nAction: bare()";

        String observation = exec.execute(thought, new Object[]{tool}, "req-test");

        assertThat(tool.bareCalls.get()).isEqualTo(1);
        assertThat(observation).contains("bare-ok");
    }

    // === hasActionLine:Action 行判定(循环终止信号) ===

    @Test
    void hasActionLineTrueWhenActionLinePresent() {
        assertThat(exec.hasActionLine("Thought: x\nAction: search(query=\"q\")")).isTrue();
    }

    @Test
    void hasActionLineFalseForPlainFinalAnswer() {
        assertThat(exec.hasActionLine("最终答案: Spring AI 是一个框架。")).isFalse();
    }

    @Test
    void hasActionLineFalseForBlankText() {
        assertThat(exec.hasActionLine("")).isFalse();
        assertThat(exec.hasActionLine(null)).isFalse();
    }

    // === describeAction:trace/history 投影 ===

    @Test
    void describeActionReturnsActionContentWhenToolCall() {
        String thought = "Thought: 搜索\nAction: search(query=\"Spring AI\")";
        assertThat(exec.describeAction(thought, true)).contains("search");
    }

    @Test
    void describeActionReturnsFinalAnswerHintWhenNoToolCall() {
        String thought = "最终答案: 完成。";
        assertThat(exec.describeAction(thought, false)).contains("最终答案");
    }

    /**
     * 测试用工具 fixture:反射扫 {@link Tool} 的目标(模拟真实 tool pack bean)。
     * 计数器/引用用于断言手动循环**真正反射调用了** @Tool 方法,且参数原样到达。
     */
    static class ExecFixtureTool {
        final AtomicInteger searchCalls = new AtomicInteger();
        final AtomicInteger bareCalls = new AtomicInteger();
        final AtomicReference<String> lastGlobPattern = new AtomicReference<>();
        final AtomicReference<String> lastShellCmd = new AtomicReference<>();

        @Tool(name = "search", description = "搜索知识库")
        public String search(String query) {
            searchCalls.incrementAndGet();
            return "search-result:" + query;
        }

        @Tool(name = "glob", description = "通配符匹配文件")
        public String glob(String pattern) {
            lastGlobPattern.set(pattern);
            return "glob-result:" + pattern;
        }

        @Tool(name = "shell", description = "执行 shell 命令")
        public String shell(String cmd) {
            lastShellCmd.set(cmd);
            return "shell-result:" + cmd;
        }

        @Tool(name = "bare", description = "无参工具")
        public String bare() {
            bareCalls.incrementAndGet();
            return "bare-ok";
        }
    }
}
