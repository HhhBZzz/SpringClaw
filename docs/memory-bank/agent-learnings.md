# Agent Learnings

<!-- schema: springclaw.agent-learning.v1 -->

当前文件用于沉淀 Agent 从执行失败、反例和验证结果中提炼出的经验。

原则：

- 只记录可复用的规则、反例和证据摘要。
- 不记录完整 prompt、敏感正文或大段工具输出。
- 新生成条目默认 `status: active`，旧格式无 status 的条目按 active 兼容。
- `disabled`、`rejected`、`superseded` 状态不会进入 Memory Bank 上下文。
- status 可以按 `signature` 更新，并记录 `reviewedAt` / `reviewReason` 作为轻量审计。
- 经验进入 Memory Bank 后会影响后续上下文，因此必须保持短、准、可审阅。
