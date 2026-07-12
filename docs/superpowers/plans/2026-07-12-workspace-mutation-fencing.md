# Workspace Mutation Fencing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SpringClaw 的工作区写工具增加 MySQL 独占租约、单调 fencing token、提交前失效阻断，以及结构化真实执行结果审计。

**Architecture:** 用一张按 canonical workspace 哈希分区的 MySQL 单行租约表串行化写执行，并以数据库时钟和递增 token 判断持有权。协调器把租约行锁保持到工具执行、Git 发布和 proposal 终态全部结束；`WorkspaceGitGuard` 在发布前校验持锁 token，并通过专用序列化器把工具返回值和 Git 元数据写入 proposal。

**Tech Stack:** Java 17、Spring Boot 3.5、Spring JDBC、MySQL 8、Flyway、Jackson、JUnit 5、Mockito、AssertJ、Maven。

## Global Constraints

- 保留现有 HEAD baseline、`targetPaths`、dirty 非目标快照与精确回滚语义。
- 租约到期判断只使用 MySQL `CURRENT_TIMESTAMP(6)`，租约行锁覆盖完整工具执行和 Git 发布。
- `fencing_token` 释放时不清零，每次过期接管严格递增。
- 旧 token 不能提交，也不能释放新持有者的租约。
- `execution_result` 必须始终是带 schema 版本的合法 JSON；大结果必须显式降级且有界。
- 不引入新的基础设施或第三方依赖。

---

### Task 1: MySQL lease persistence and workspace identity

**Files:**
- Create: `src/main/resources/db/migration/V5__workspace_mutation_lease.sql`
- Create: `src/main/resources/db/migration/V6__expand_tool_execution_result.sql`
- Create: `src/main/java/com/springclaw/service/workspace/WorkspaceMutationLease.java`
- Create: `src/main/java/com/springclaw/service/workspace/WorkspaceIdentity.java`
- Create: `src/main/java/com/springclaw/service/workspace/WorkspaceMutationLeaseRepository.java`
- Test: `src/test/java/com/springclaw/service/workspace/WorkspaceIdentityTest.java`
- Test: `src/test/java/com/springclaw/service/workspace/WorkspaceMutationLeaseRepositoryTest.java`
- Test: `src/test/java/com/springclaw/service/workspace/WorkspaceMutationLeaseConcurrencyTest.java`

**Interfaces:**
- Produces: `WorkspaceIdentity.id(Path): String`。
- Produces: `WorkspaceMutationLeaseRepository.acquire(String workspaceId, String proposalId, Duration ttl): Optional<WorkspaceMutationLease>`。
- Produces: `isCurrent(String workspaceId, String proposalId, long token): boolean` 和 `release(String workspaceId, String proposalId, long token): boolean`。

- [ ] **Step 1: Write failing identity and repository tests**

覆盖同一路径规范化稳定、不同路径 ID 不同；首次 acquire 返回 token 1、第二持有者被拒绝；把首租约改为过期后接管返回更大的 token；旧 token 的 current/release 返回 false 且新租约仍有效。另用两个真实线程和独立事务证明第一个执行回调结束前第二个回调不能进入。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -q -Dtest=WorkspaceIdentityTest,WorkspaceMutationLeaseRepositoryTest test`

Expected: FAIL，原因是迁移、记录或仓库类型尚不存在。

- [ ] **Step 3: Add the Flyway table and minimal transactional repository**

迁移创建 `workspace_mutation_lease(workspace_id, holder_proposal_id, fencing_token, lease_until, update_time)`，并把 proposal `execution_result` 扩为 `MEDIUMTEXT`。仓库的 acquire 使用 `INSERT IGNORE`、事务内 `SELECT ... FOR UPDATE` 和条件更新；current/release 同时匹配 workspace、proposal、token。

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `mvn -q -Dtest=WorkspaceIdentityTest,WorkspaceMutationLeaseRepositoryTest test`

Expected: PASS；Flyway 验证 6 个 migration。

- [ ] **Step 5: Commit the lease persistence increment**

```bash
git add src/main/resources/db/migration/V5__workspace_mutation_lease.sql src/main/java/com/springclaw/service/workspace/WorkspaceMutationLease.java src/main/java/com/springclaw/service/workspace/WorkspaceIdentity.java src/main/java/com/springclaw/service/workspace/WorkspaceMutationLeaseRepository.java src/test/java/com/springclaw/service/workspace/WorkspaceIdentityTest.java src/test/java/com/springclaw/service/workspace/WorkspaceMutationLeaseRepositoryTest.java
git commit -m "feat: add workspace mutation fencing lease"
```

### Task 2: Lease coordinator and execution-result serializer

**Files:**
- Create: `src/main/java/com/springclaw/service/workspace/WorkspaceMutationLeaseCoordinator.java`
- Create: `src/main/java/com/springclaw/service/workspace/ToolExecutionResultSerializer.java`
- Test: `src/test/java/com/springclaw/service/workspace/WorkspaceMutationLeaseCoordinatorTest.java`
- Test: `src/test/java/com/springclaw/service/workspace/ToolExecutionResultSerializerTest.java`

**Interfaces:**
- Consumes: Task 1 的 identity 和 repository。
- Produces: `executeExclusive(Path workspaceRoot, String proposalId, LeaseWork<T> work): T`，以 `@Transactional(rollbackFor=Exception.class)` 覆盖完整 work；以及持锁事务内的 `assertCurrent(WorkspaceMutationLease): void`。
- Produces: `serialize(ToolInvocationProposal proposal, Object result, long token, String commitSha, List<String> changedFiles, boolean noOp): String`。

- [ ] **Step 1: Write failing coordinator and serializer tests**

验证 coordinator 使用配置 TTL、完整回调在事务方法内执行、失效 token 抛安全异常、释放携带精确 token；serializer 的 JSON 包含 schema、真实返回值、token、commit、changed files、no-op，并对不可序列化或超大结果生成有界合法 JSON。

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -q -Dtest=WorkspaceMutationLeaseCoordinatorTest,ToolExecutionResultSerializerTest test`

Expected: FAIL，原因是两个协作组件尚不存在。

- [ ] **Step 3: Implement the coordinator and serializer**

Coordinator 用 `@Value` 注入 300 秒执行租约，并通过 Spring 事务把 acquire 的 `SELECT FOR UPDATE` 行锁保持到回调完成。Serializer 用应用 `ObjectMapper` 构造 `springclaw.tool-execution-result.v1` envelope；结果 JSON 超过 32768 UTF-8 字节时替换为最多 4096 字符的摘要并写 `resultTruncated=true`。

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `mvn -q -Dtest=WorkspaceMutationLeaseCoordinatorTest,ToolExecutionResultSerializerTest test`

Expected: PASS。

- [ ] **Step 5: Commit the coordination increment**

```bash
git add src/main/java/com/springclaw/service/workspace/WorkspaceMutationLeaseCoordinator.java src/main/java/com/springclaw/service/workspace/ToolExecutionResultSerializer.java src/test/java/com/springclaw/service/workspace/WorkspaceMutationLeaseCoordinatorTest.java src/test/java/com/springclaw/service/workspace/ToolExecutionResultSerializerTest.java
git commit -m "feat: coordinate fenced workspace execution"
```

### Task 3: Fence WorkspaceGitGuard commits and persist real results

**Files:**
- Modify: `src/main/java/com/springclaw/service/workspace/WorkspaceGitGuard.java`
- Modify: `src/test/java/com/springclaw/service/workspace/WorkspaceGitGuardTest.java`

**Interfaces:**
- Consumes: Task 2 的 coordinator 和 serializer。
- Preserves: `public <T> T execute(ToolInvocationProposal proposal, Callable<T> toolExecution) throws Exception`。
- Produces: 在每条返回/异常路径上正确管理租约，并把结构化 JSON 交给 `ToolInvocationProposalRepository.recordCommit`。

- [ ] **Step 1: Extend WorkspaceGitGuard tests for lease behavior**

为现有 fixture 注入 coordinator/serializer；验证独占事务在 `headSha` 前进入；变更和 no-op 都在成功终态前校验当前 token；所有权校验失败时不回滚、不 add、不 commit、不 recordCommit。

- [ ] **Step 2: Extend tests for structured real results**

捕获 `recordCommit` 第四个参数并解析 JSON，断言真实工具返回值、schema、proposalId、toolName、fencingToken、noOp、commitSha 和 changedFiles。

- [ ] **Step 3: Run the guard test to verify RED**

Run: `mvn -q -Dtest=WorkspaceGitGuardTest test`

Expected: FAIL，因为 guard 尚未调用租约和 serializer。

- [ ] **Step 4: Add exclusive execution, ownership assertion, and structured result recording**

在 execute 最外层调用 coordinator 的独占事务；越界校验通过后 assertCurrent；所有权不确定时不再修改共享工作区；成功提交或 no-op 后调用 serializer，把返回 JSON 传给 recordCommit。

- [ ] **Step 5: Run guard and gateway regression tests**

Run: `mvn -q -Dtest=WorkspaceGitGuardTest,DefaultToolGatewayTest,ToolInvocationProposalRepositoryTest test`

Expected: PASS。

- [ ] **Step 6: Commit the guard integration**

```bash
git add src/main/java/com/springclaw/service/workspace/WorkspaceGitGuard.java src/test/java/com/springclaw/service/workspace/WorkspaceGitGuardTest.java
git commit -m "feat: fence workspace git commits"
```

### Task 4: Full verification and branch integration

**Files:**
- Modify only files required by failures discovered during verification.

**Interfaces:**
- Consumes: Tasks 1-3 的完整行为。
- Produces: 可合并、完整测试通过的功能分支和目标分支。

- [ ] **Step 1: Run all Maven tests**

Run: `mvn test`

Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 2: Review migration and diff**

Run: `git diff b918cc4...HEAD --check && git diff --stat b918cc4...HEAD && git status --short`

Expected: diff check 无输出；只有计划内文件；工作树 clean。

- [ ] **Step 3: Stop on any verification failure**

如果测试或 diff 检查失败，不创建笼统的“verification fix”；回到失败所属的 Task 1、2 或 3，补充能够复现问题的测试，完成 RED/GREEN 后用该任务规定的文件清单和提交信息提交。没有失败时直接进入合并，不创建空 commit。

- [ ] **Step 4: Merge into the target branch and rerun all tests**

在 `/Users/hanbingzheng/springclaw` 的 `codex/flyway-schema-migration` 上合并 `codex/workspace-fencing`，再运行 `mvn test`。

Expected: 合并成功；BUILD SUCCESS，0 failures，0 errors；保留主工作区既有未跟踪目录。

- [ ] **Step 5: Push and clean the isolated worktree**

Push `codex/flyway-schema-migration` 到 origin，移除 `.worktrees/workspace-fencing`，删除已合并的本地功能分支。
