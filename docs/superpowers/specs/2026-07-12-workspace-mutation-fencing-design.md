# Workspace Mutation Fencing Design

## 背景

SpringClaw 已经把高风险写工具统一收敛到 `ToolGateway -> ToolRuntimeAspect -> WorkspaceGitGuard`，并能校验 proposal 创建时的 Git HEAD、限制 `targetPaths`、自动提交和精确回滚。但多个执行线程或多个应用实例仍可能同时修改同一个工作区：先启动的旧执行者可能在超时后恢复，并在新的执行者之后提交。这种“僵尸写入”不能仅靠 JVM 锁或普通分布式锁解决。

此外，proposal 的 `execution_result` 当前只写入 `"ok"` 或 no-op 文案，无法回答工具实际返回了什么、由哪个写租约提交、是否发生 Git 变更。

## 目标

- 同一物理工作区同一时刻最多只有一个有效写执行者。
- 每次成功获取租约都获得严格递增的 fencing token。
- 租约过期后，旧执行者不能续签提交窗口、不能执行 `git add/commit`，也不能释放新执行者的租约。
- 写工具成功后，proposal 持久化版本化、结构化的真实执行结果。
- 保留现有 HEAD、脏文件、目标路径和回滚不变量。

## 非目标

- 不引入 Redis 锁、ZooKeeper 或新的基础设施。
- 不实现跨多个 Git 仓库的分布式事务。
- 不重构 proposal 状态机或工具调用协议。
- 不保证任意长时间阻塞的 `git commit` 与数据库租约具有理论上的原子提交；通过提交前原子续签和充足的短提交窗口把风险限定在本地 Git 命令超时内。

## 总体设计

### 1. MySQL 工作区租约

新增 `workspace_mutation_lease` 表，一行代表一个物理工作区：

- `workspace_id`：工作区真实路径规范化后计算 SHA-256，避免符号链接和路径长度问题。
- `holder_proposal_id`：当前持有者。
- `fencing_token`：永不回退、释放时不清零的 `BIGINT`。
- `lease_until`：数据库时钟下的过期时间。
- `update_time`：最后变更时间。

获取租约在单个数据库事务内完成：先 `INSERT IGNORE` 初始化行，再 `SELECT ... FOR UPDATE`。只有无持有者或 `lease_until <= CURRENT_TIMESTAMP(6)` 时才能把 token 加一并设置新持有者。活跃租约存在时立即拒绝，不等待业务级重试，避免请求线程无限阻塞。

所有到期判断和续签都使用 MySQL `CURRENT_TIMESTAMP(6)`，不依赖应用实例时钟。

### 2. 提交 fencing

`WorkspaceGitGuard.execute` 在读取 HEAD 前获取租约，并在 `finally` 中按 `(workspaceId, proposalId, token)` 条件释放。

工具返回且越界检查通过后，守卫调用 `renewForCommit`：只有数据库中的持有者、token、未过期条件全部匹配时，才把租约延长为提交窗口。续签失败代表当前执行者已经过期或被替代，守卫精确回滚 proposal 的目标路径并抛出 `SecurityException`，不会调用 `git add`、`git commit` 或成功终态写入。

no-op 执行也必须通过同一 fencing 检查，防止旧执行者覆盖 proposal 的审计终态。释放操作同样带 token 条件，因此旧执行者的 `finally` 不能清掉新租约。

默认执行租约为 5 分钟，提交窗口为 30 秒；本地 Git 命令已有 15 秒超时，所以有效续签覆盖完整提交过程。两个值通过 `springclaw.workspace.mutation-lease-seconds` 和 `springclaw.workspace.commit-lease-seconds` 配置。

### 3. 真实执行结果

新增专用 `ToolExecutionResultSerializer`，把成功返回值编码为 `springclaw.tool-execution-result.v1` JSON：

```json
{
  "schema": "springclaw.tool-execution-result.v1",
  "proposalId": "tip-...",
  "toolName": "WorkspaceEditToolPack.workspaceWriteFile",
  "success": true,
  "fencingToken": 7,
  "noOp": false,
  "gitCommitSha": "abc...",
  "changedFiles": ["src/A.java"],
  "resultType": "java.lang.String",
  "result": "written"
}
```

普通结果优先用 Jackson 保存为 JSON 值。序列化失败或结果超过 32 KiB 时降级为长度受限的字符串摘要，并显式写入 `resultTruncated=true`，保证 `execution_result` 始终是合法 JSON 且不会撑爆 MySQL `TEXT`。`null` 返回值作为 JSON `null` 保存。

失败仍由现有 `execution_error` 保存异常摘要，避免改变现有失败状态机。

## 失败与恢复语义

- 获取租约失败：工具不执行，工作区不变化。
- 工具抛异常：沿用目标路径回滚和 dirty 非目标文件快照恢复，然后带 token 释放租约。
- 工具越界：沿用精确回滚，然后带 token 释放租约。
- 提交续签失败：回滚目标路径、恢复 dirty 非目标文件，不 stage、不 commit、不写成功终态。
- `recordCommit` CAS 失败但 Git 已提交：沿用现有错误日志；租约解决的是工作区并发，不替代 proposal 状态 CAS。
- 进程崩溃：租约自然过期；下一持有者获得更大的 token。

## 测试策略

- MySQL 集成测试验证互斥获取、过期接管时 token 递增、旧 token 不能续签或释放新租约。
- 单元测试验证工作区路径身份稳定且不同路径不碰撞。
- `WorkspaceGitGuardTest` 验证租约生命周期、提交前 fencing、fencing 丢失时回滚且不提交、no-op 也 fencing、结构化结果包含真实返回值/commit/changed files/token。
- 先运行聚焦测试，再运行完整 Maven 测试套件；最后在目标分支合并结果上再次完整回归。
