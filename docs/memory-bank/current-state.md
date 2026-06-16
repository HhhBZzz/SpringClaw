# Current State

当前稳定策略：

- 不继续合并 `AgentRuntimeEngine`、`AutonomousLoopEngine`、`OparLoopEngine`。
- 保留多 engine 并存，先把触发条件、权限边界、trace 结果记录清楚。
- Tool audit、workspace guard、confirmation timeline、workspace tool input summary 已开始结构化。
- Memory 方向开始从向量召回转向 Memory Bank + Context 可解释。
- 自进化方向开始从失败 trace 提炼经验，写入 `agent-learnings.md`，通过 status 过滤控制哪些规则进入下一轮上下文，并提供 ADMIN 后端入口列表化 review 条目、按 signature 更新 status、记录 reviewedAt/reviewReason；review DTO 已从反例和证据中派生 counterexample category，并派生 context impact 表达规则当前是否会进入上下文；Runtime Console 已有 Learning 入口可按状态筛选、查看反例类型和上下文影响、填写 review reason，并 approve/disable/reject、恢复 active 或标记 superseded。
- Knowledge Source 方向已新增只读 Markdown snapshot 底座：Wiki.js / Obsidian 导出的 Markdown 只有 front matter `status: active/approved` 才会进入 project knowledge snapshot；它尚未接入 RAG，也不写入用户长期记忆。

下一步优先级：

1. 继续补 tool duration、tool error reason、memory recall hit count。
2. 在现有 learning list/status/filter/category/context impact 前后端闭环基础上，把 learning 影响复盘继续关联到后续成功/失败 trace。
3. 让 Obsidian / Wiki.js 这类 Markdown 知识源继续走受控 Knowledge Source review/list 或上下文注入点，而不是污染用户长期记忆。
