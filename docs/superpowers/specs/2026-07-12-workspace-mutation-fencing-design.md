# Workspace Mutation Fencing Design

## 背景

SpringClaw 已经把高风险写工具统一收敛到 `ToolGateway -> ToolRuntimeAspect -> WorkspaceGitGuard`，并能校验 proposal 创建时的 Git HEAD、限制 `targetPaths`、自动提交和精确回滚。但多个执行线程或多个应用实例仍可能同时修改同一个工作区：先启动的旧执行者可能在超时后恢复，并在新的执行者之后提交。这种“僵尸写入”不能仅靠 JVM 锁或普通分布式锁解决。

此外，proposal 的 `execution_result` 当前只写入 `"ok"` 或 no-op 文案，无法回答工具实际返回了什么、由哪个写租约提交、是否发生 Git 变更。

## 目标

- 同一物理工作区同一时刻最多只有一个有效写执行者。
- 每次成功获取租约都获得严格递增的 fencing token。
- 租约过期或所有权校验失败后，旧执行者不能执行 `git add/commit`，也不能释放新执行者的租约。
- 写工具成功后，proposal 持久化版本化、结构化的真实执行结果。
- 保留现有 HEAD、脏文件、目标路径和回滚不变量。

## 非目标

- 不引入 Redis 锁、ZooKeeper 或新的基础设施。
- 不实现跨多个 Git 仓库的分布式事务。
- 不重构 proposal 状态机或工具调用协议。
- 不实现 Git 对象库与 MySQL proposal 终态之间的跨资源原子提交；若 Git 成功而数据库事务随后失败，依靠 HEAD baseline 阻止后续旧 proposal 继续执行并要求恢复流程介入。

## 总体设计

### 1. MySQL 工作区租约

新增 `workspace_mutation_lease` 表，一行代表一个物理工作区：

- `workspace_id`：工作区真实路径规范化后计算 SHA-256，避免符号链接和路径长度问题。
- `holder_proposal_id`：当前持有者。
- `fencing_token`：永不回退、释放时不清零的 `BIGINT`。
- `lease_until`：数据库时钟下的过期时间。
- `update_time`：最后变更时间。

V7 新增单行全局 token counter。每次执行先在 `REQUIRES_NEW` 独立事务中持久分配 token，即使后续工具事务失败或进程异常，该 token 也已永久消耗，下一次获取必然更大。counter 初值从现有租约表的最大 token 初始化，升级过程不会倒退。

工作区行在短 `REQUIRES_NEW` 事务中按需初始化；整个工具执行位于另一个数据库事务，使用 `SELECT ... FOR UPDATE NOWAIT` 获取工作区行锁，并把锁一直持有到工具执行、Git 发布和 proposal 成功终态全部完成。竞争执行者快速失败，不占用连接等待任意时长的工具任务，也不能在旧执行者仍存活时接管共享工作区。

所有到期判断都使用 MySQL `CURRENT_TIMESTAMP(6)`，不依赖应用实例时钟。

### 2. 提交 fencing

`WorkspaceGitGuard.execute` 通过 `WorkspaceMutationLeaseCoordinator.executeExclusive` 在读取 HEAD 前获取租约。Coordinator 的 Spring 事务覆盖完整回调，并在回调结束时按 `(workspaceId, proposalId, token)` 条件释放；事务提交后竞争者才能继续。

工具返回且越界检查通过后，守卫再次校验数据库中的持有者、token 和数据库到期时间。该校验与后续 `git add`、`git commit`、`recordCommit` 位于同一持锁事务，因此校验后不会有更高 token 的持有者插入。校验失败或数据库连接丢失时，旧执行者停止发布且不再修改共享工作区，避免“旧执行者回滚”覆盖新持有者的合法结果。

no-op 执行也必须通过同一持锁 fencing 检查，防止旧执行者覆盖 proposal 的审计终态。释放操作同样带 token 条件。

默认执行租约为 5 分钟，通过 `springclaw.workspace.mutation-lease-seconds` 配置。租约到期校验限制单次工具执行时长；数据库行锁而非一个容易跨越的短提交窗口负责阻止并发接管。

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

普通结果优先用 Jackson 保存为 JSON 值。序列化失败或结果超过 32 KiB 时降级为长度受限的字符串摘要，并显式写入 `resultTruncated=true`。V6 把 `execution_result` 扩为 `MEDIUMTEXT`，保证结果和 changed-files envelope 不会在 Git 已提交后因 64 KiB `TEXT` 上限写库失败。`null` 返回值作为 JSON `null` 保存。

失败仍由现有 `execution_error` 保存异常摘要，避免改变现有失败状态机。

## 失败与恢复语义

- 获取租约失败：已分配 token 被永久跳过，工具不执行，工作区不变化。
- 工具抛异常：沿用目标路径回滚和 dirty 非目标文件快照恢复，然后带 token 释放租约。
- 工具越界：沿用精确回滚，然后带 token 释放租约。
- publication fencing 校验失败：不 stage、不 commit、不写成功终态，也不由失去所有权的旧执行者回滚共享工作区。
- `recordCommit` CAS 失败但 Git 已提交：沿用现有错误日志；租约解决的是工作区并发，不替代 proposal 状态 CAS。
- 进程崩溃：数据库释放行锁，租约自然过期；独立提交的 token 不回滚，下一持有者获得更大的 token。

## 测试策略

- MySQL 集成测试验证互斥获取、过期接管和失败回滚后的 token 仍递增、旧 token 不能校验或释放新租约，并用两个独立事务证明竞争者 NOWAIT 快速失败、重试后才能进入。
- 单元测试验证工作区路径身份稳定且不同路径不碰撞。
- `WorkspaceGitGuardTest` 验证租约生命周期、提交前 fencing、fencing 丢失时回滚且不提交、no-op 也 fencing、结构化结果包含真实返回值/commit/changed files/token。
- 先运行聚焦测试，再运行完整 Maven 测试套件；最后在目标分支合并结果上再次完整回归。
