# Progress

已完成的稳定基础：

- Workspace Guard 拒绝原因结构化。
- 流式写操作确认链路补强。
- Product Mode metadata 持久化并在控制台展示。
- Timeline step schema 增加 `category/action/target/source/riskLevel`。
- 用户确认和 workspace 动作进入 timeline。
- Workspace 工具输入摘要进入 audit / trace。
- Vector memory 召回结果增加 session/user 防御性过滤。
- Memory Bank 文件化项目记忆接入 ContextAssembler。
- Agent 循环改写问题时保留 Memory Bank。
- 历史问题提取避免把 Memory Bank 误当成用户问题。
- Agent learning 最小闭环已接入：失败 trace 可沉淀到 `agent-learnings.md`，并在 Memory Bank 中优先进入上下文。
- Agent learning 条目已带 `status: active`，Memory Bank 读取时会过滤 `disabled/rejected/superseded` 条目。

当前正在推进：

- Context 可解释与低基数观测。
- 自进化经验沉淀，而不是只保存对话。
- Harness 化，而不是继续扩 demo。
