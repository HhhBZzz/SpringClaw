# Current State

当前稳定策略：

- 不继续合并 `AgentRuntimeEngine`、`AutonomousLoopEngine`、`OparLoopEngine`。
- 保留多 engine 并存，先把触发条件、权限边界、trace 结果记录清楚。
- Tool audit、workspace guard、confirmation timeline、workspace tool input summary 已开始结构化。
- Memory 方向开始从向量召回转向 Memory Bank + Context 可解释。
- 自进化方向开始从失败 trace 提炼经验，写入 `agent-learnings.md`，通过 status 过滤控制哪些规则进入下一轮上下文，并在 context summary 中暴露 active/filtered learning count。

下一步优先级：

1. 继续补 tool duration、tool error reason、memory recall hit count。
2. 在现有 learning status 基础上补用户确认、撤销和反例修正入口。
3. 让 Obsidian / Wiki.js 这类 Markdown 知识源进入受控 Knowledge Source，而不是污染用户长期记忆。
