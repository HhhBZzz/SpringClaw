# Current State

当前稳定策略：

- 不继续合并 `AgentRuntimeEngine`、`AutonomousLoopEngine`、`OparLoopEngine`。
- 保留多 engine 并存，先把触发条件、权限边界、trace 结果记录清楚。
- Tool audit、workspace guard、confirmation timeline、workspace tool input summary 已开始结构化。
- Memory 方向开始从向量召回转向 Memory Bank + Context 可解释。

下一步优先级：

1. 让 ContextAssembler 清楚记录本轮使用的上下文来源。
2. 让 workspace 修改进入 diff / patch / rollback 设计。
3. 让前端 Command Center 展示真实 plan、timeline、tool calls、confirmation、logs。
