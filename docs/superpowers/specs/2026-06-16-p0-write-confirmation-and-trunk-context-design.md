# P0：写操作确认闭环 + 干路上下文注入

> Date: 2026-06-16
> Branch: codex/bootstrap-github
> Scope: P0 安全级 + 干路上下文注入（不动 AgentEngine 接口、不合并引擎、不做完整 PolicyService）
> Status: 设计已定稿，允许落地

---

## 1. 背景

2026-06-16 晚发现一次真实事故：用户提问"OPAR 是怎么做的"（intent=general，普通知识问答），agent 在 OparLoopEngine 的 action 阶段调用了 `WorkspaceEditToolPack.workspaceWriteFile`，把 `OparLoopEngine.java` 从 527 行覆盖成 86 行的幻觉代码。整个过程没有触发任何用户确认。

事后审计五道检查全部失守：

1. `AgentDecisionService` 判断 intent=general、riskLevel=read，但 ToolOrchestrator 仍按 question 关键词把 WorkspaceEditToolPack 挂上
2. `ChatServiceImpl.shouldRequestActionConfirmation` 只检查 `decision.requiresConfirmation()`——意图阶段已经预测为不需要确认
3. `ToolRuntimeAspect` 检查权限和速率限制，不检查"这个工具调用是否匹配本次请求的 intent"
4. `WorkspaceGuard` 检查路径边界和危险命令，但对工作区内合法路径放行
5. 没有 diff、备份、rollback——写完无法恢复

事故的根因不是单点 bug，是架构缺陷：**写操作的确认逻辑是"意图判断时预测的 risk"，不是"工具实际调用瞬间的真实风险"**。

并行存在第二个根因：5/6 的引擎扔掉了 ContextAssembler 的产出，只取 `assembled.question()`。`renderBasicChatPrompt` 只塞当前问题给模型——短期事件、Memory Bank、学习规则、语义召回全部丢失。结果是普通对话答非所问、agent 不知道用户上一句说了什么。

本设计同时修这两个问题。

---

## 2. 目标与非目标

### 目标

1. **写操作必经确认**：write/dangerous 风险级别的工具调用，没有对应 approved proposal 不能落盘
2. **写后可回滚**：每个写操作产生独立的 git commit，可 `git revert` 单点回退
3. **干路上下文注入**：所有引擎的 prompt 都消费同一份 `ContextInjection`，不再各自只取 question
4. **可审计**：proposal 持久化到 DB，状态机覆盖正常路径和异常路径，trace 关联 proposalId 和 commit_sha
5. **不阻塞线程**：等待用户确认期间不持有线程锁、不挂起 SSE、不卡 emitter
6. **测试覆盖**：未确认禁止写、确认后允许写、拒绝不写、args 篡改不写、重复确认不重复执行、所有引擎都读到 ContextInjection

### 非目标

1. ❌ 合并 6 个引擎到 2-3 个（推迟到 P1）
2. ❌ 完整 PolicyService 统一所有策略（推迟到 P1）
3. ❌ ToolOrchestrator 按 intent×risk 严格限制工具集（推迟到 P1，本轮只加最小 deny rule）
4. ❌ 减少一次请求的 LLM 调用次数（P2）
5. ❌ 修复 Memory tab 接真实数据（P3）
6. ❌ 改 `AgentEngine` 接口（保持不变，只改实现里的 prompt 拼接）

---

## 3. 核心定义

> Proposal 是持久化的、一次性的、冻结后的工具调用授权单。
>
> 它不是"用户同意了一类行为"，而是：用户同意了某个 requestId/runId 下，某个 toolName，携带某组 canonical arguments，修改某些 targetPaths，执行一次。

### 核心不变量

整个状态机围绕这 16 条不变量：

**Proposal 与执行授权**
1. **Proposal 创建后冻结** `toolName + toolsetId + canonicalArgs + argsHash + targetPaths`，永不修改
2. **用户确认的是冻结的 snapshot**，不是模型意图
3. **confirm 后不重跑模型**，只执行 snapshot 里的工具调用
4. **Aspect 只认后端 ExecutionContext**，不认前端 token、不认 HTTP 参数
5. **Aspect 执行前必须回查 DB**，且 status 必须是 EXECUTING
6. **当前工具调用的 hash 必须等于 proposal.argumentsHash**
7. **工具实际改动文件必须是 targetPaths 的子集**（newlyChangedFiles ⊆ targetPaths）
8. **EXECUTED / REJECTED / EXPIRED / CANCELLED / FAILED 均不可再次执行**
9. **FAILED 不重试原 proposal**，重试必须创建新 proposal

**Git baseline 一致性**
10. **Proposal 创建时记录 `git_head_sha_at_create`**，执行前 HEAD 必须一致，否则拒绝执行（baseline 失效）

**ToolInvoker 安全**
11. **ToolInvoker.invoke(snapshot) 必须重新进入 `ToolRuntimeAspect`**，不能绕过最终门禁。CI 测试必须验证 confirm resume 路径命中 Aspect

**路径规范化**
12. **targetPaths / dirtyFiles / changedFiles 必须统一为 repo-relative normalized path**，禁止混用绝对路径、相对路径、`./` 前缀、`..` 段

**Stuck 状态处理**
13. **stuck EXECUTING 不自动重放**，只标记 FAILED，用户重新生成 proposal（避免部分落盘后重复写）

**No-op 写入**
14. **写工具成功但无实际 diff 时**，proposal=EXECUTED，`git_commit_sha` 允许为 NULL，不强制 `--allow-empty`

**回滚精确性**
15. **rollback/clean 只处理 `newlyChangedFiles` 中由本次工具产生的文件**，不碰用户原 dirty 文件。tracked 文件用 `git checkout`，untracked 文件用文件删除

**事件命名语义**
16. **confirm 后发布的事件命名为 `ProposalExecutionRequestedEvent`**，反映状态已是 EXECUTING（不是 ProposalApprovedEvent，因为 APPROVED 在事务内是瞬时状态）

---

## 4. 架构总览

```
                    ┌───────────────┐
                    │ ChatRequest   │
                    └───────┬───────┘
                            ▼
                    ┌───────────────┐
                    │ ChatContext   │  改造点 1: 加 ContextInjection 字段
                    │  Factory      │
                    └───────┬───────┘
                            ▼
                    ┌───────────────┐
                    │ Engine Select │
                    └───────┬───────┘
                            ▼
   ┌────────────────────────┴────────────────────────┐
   ▼                ▼                ▼                ▼
 Basic         Runtime          Autonomous        OPAR / etc
   │              │                  │                │
   └──────────────┴───────┬──────────┴────────────────┘
                          │
              改造点 2: 5 个引擎统一读 ContextInjection.renderForPrompt()
                          │
                          ▼
                   ┌─────────────┐
                   │ ChatClient  │
                   │  调模型     │
                   └──────┬──────┘
                          │
                  模型决定调工具
                          │
                          ▼
                ┌──────────────────┐
                │ ToolRuntime      │  改造点 3: 事件化最终门禁
                │   Aspect         │
                └──────┬───────────┘
                       │
              read? ───┼─→ 直接执行
                       │
        write/dang ────┼─→ 读 ToolExecutionContextHolder
                       │
              ctx 不存在 ┴─→ canonicalize args
                              │  hash(toolName+toolsetId+args)
                              │  解析 targetPaths
                              │  检查 dirtyFiles ∩ targetPaths
                              │  createProposal(PENDING)
                              │  抛 PendingApprovalException
                              │
              ctx 存在 ──────┴─→ 回查 DB (status=EXECUTING)
                                  │  校验 toolName/argsHash/requestId/runId/userId
                                  │
                                  ▼
                           ┌────────────┐
                           │ GitGuard   │  改造点 5
                           │ before     │
                           └────┬───────┘
                                ▼
                            tool.proceed()
                                ▼
                           ┌────────────┐
                           │ GitGuard   │
                           │ after      │
                           └────┬───────┘
                                ▼
                          newlyChanged ⊆ targetPaths?
                                ▼
                          git add targetPaths + commit
                                ▼
                           proposal → EXECUTED

引擎/ChatServiceImpl 捕获 PendingApprovalException:
  → run.status = WAITING_CONFIRMATION
  → SSE action_required
  → 释放锁和 emitter
  → 等待用户操作

POST /api/proposals/{id}/confirm        改造点 4: confirm/reject API
  → 事务内 PENDING → APPROVED → EXECUTING
  → 异步 ToolInvoker.invoke(snapshot)
  → 重新进入 Aspect 二次校验
```

---

## 5. 七个改造点

### 改造点 1：ChatContext 加 ContextInjection

**目标**：所有引擎共享同一个上下文注入入口。

```java
public record ContextInjection(
    String observePrompt,           // ContextAssembler 现有产出
    String policyPrompt,            // P0 占位空字符串，P1 接 PolicyService
    String pendingProposalPrompt,   // P0 占位空字符串，P1 提示 agent 有未处理 proposal
    Map<String, Object> metadata    // contextSummary 等元数据
) {
    public static ContextInjection empty() {
        return new ContextInjection("", "", "", Map.of());
    }
    
    public String renderForPrompt() {
        return Stream.of(observePrompt, policyPrompt, pendingProposalPrompt)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining("\n\n"));
    }
}
```

ChatContext 加字段：

```java
public record ChatContext(
    // ... 现有字段
    AssembledContext assembled,
    ContextInjection contextInjection   // ← 新增
) {}
```

ChatContextFactory 填充：

```java
ContextInjection injection = new ContextInjection(
    assembled.observePrompt(),
    "",  // P0 占位
    "",  // P0 占位
    Map.of("contextSummary", assembled.sourceSummary())
);
```

### 改造点 2：5 个引擎读 ContextInjection

只改 prompt 拼接方法，不改 AgentEngine 接口。

**改造对象**：
- `BasicStreamEngine.renderBasicChatPrompt()`
- `AgentRuntimeEngine.renderReflectionPrompt()` 和 `renderSummaryPrompt()`
- `AutonomousLoopEngine.renderAutonomousPrompt()`
- `ModelLedStreamEngine.renderModelLedPrompt()`
- `SimplifiedOparEngine.renderUserPrompt()`

`OparLoopEngine` 已经在用 observePrompt，不需要改。

**改造模式**（以 BasicStreamEngine 为例）：

```java
private String renderBasicChatPrompt(ChatContext ctx) {
    ContextInjection inj = ctx.contextInjection();
    return """
            %s
            
            ## 用户当前问题
            %s
            
            直接回答用户问题，保持中文自然、完整、有重点。
            这是普通聊天路径：不要调用工具。
            """.formatted(
                inj.renderForPrompt(),
                ctx.assembled().question()
            );
}
```

**测试约束**：每个引擎都要有一条断言"渲染后的 prompt 包含 observePrompt 内容"的测试。

### 改造点 3：ToolRuntimeAspect 加最终风险门禁

```java
@Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
public Object guard(ProceedingJoinPoint pjp) throws Throwable {
    String toolName = resolveToolName(pjp);
    String toolsetId = resolveToolsetId(toolName);
    Object[] args = pjp.getArgs();
    String riskLevel = riskPolicyService.classify(toolName);
    
    // 现有检查保持不变
    permissionService.checkPermission(toolName);
    guardService.checkRateLimit(toolName);
    
    // read/safe 直接放行
    if (!isWriteOrDangerous(riskLevel)) {
        return pjp.proceed();
    }
    
    // 读 ThreadLocal 里的授权上下文
    ApprovedProposalContext ctx = ToolExecutionContextHolder.getApprovedProposal();
    
    if (ctx == null) {
        // 创建 proposal、抛异常（不发 SSE）
        ToolInvocationSnapshot snapshot = snapshotService.capture(
            toolName, toolsetId, args, riskLevel
        );
        AgentActionProposal proposal = proposalService.create(snapshot);
        throw new PendingApprovalException(proposal.proposalId());
    }
    
    // 二次校验：回查 DB
    AgentActionProposal latest = proposalRepository.findByProposalId(ctx.proposalId());
    if (latest == null) throw new SecurityException("proposal 不存在");
    if (latest.status() != EXECUTING) throw new SecurityException("proposal 状态非法: " + latest.status());
    if (!latest.toolName().equals(toolName)) throw new SecurityException("toolName 不匹配");
    if (!latest.requestId().equals(ctx.requestId())) throw new SecurityException("requestId 不匹配");
    if (!latest.runId().equals(ctx.runId())) throw new SecurityException("runId 不匹配");
    if (!latest.userId().equals(ctx.userId())) throw new SecurityException("userId 不匹配");
    
    String currentArgsHash = canonicalize(toolName, toolsetId, args);
    if (!latest.argumentsHash().equals(currentArgsHash)) throw new SecurityException("args 被篡改");
    
    // GitGuard 包住执行
    return gitGuard.execute(latest, () -> pjp.proceed());
}
```

**关键约束**：
- Aspect 不发 SSE，不知道 SSE 存在
- Aspect 只信 `ToolExecutionContextHolder`，不信前端传的任何 token
- 校验顺序：DB 状态 → toolName → requestId/runId/userId → argsHash
- 任一校验失败抛 `SecurityException`，不抛 `BusinessException`

### 改造点 4：Proposal 持久化（DB 表 + 状态机）

**DB 表**：`agent_action_proposal`

```sql
CREATE TABLE agent_action_proposal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    proposal_id VARCHAR(64) UNIQUE NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    session_key VARCHAR(128) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    role_code VARCHAR(32),
    
    -- 工具调用快照（一表合一，不拆 snapshot 表）
    tool_name VARCHAR(128) NOT NULL,
    toolset_id VARCHAR(64) NOT NULL,
    arguments_canonical_json TEXT NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    target_paths TEXT NOT NULL,                -- JSON array
    preview_summary VARCHAR(1024),
    
    -- workspace 状态快照
    workspace_dirty_at_create BOOLEAN DEFAULT 0,
    dirty_files_at_create TEXT,                -- JSON array
    
    -- 状态机
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,            -- 乐观锁
    
    -- 执行结果
    executed_at DATETIME,
    execution_result TEXT,
    execution_error VARCHAR(1024),
    
    -- Git 闭环
    git_head_sha_at_create VARCHAR(40),         -- proposal 创建瞬间的 HEAD（baseline 校验依据）
    git_baseline_sha VARCHAR(40),               -- 执行开始时的 HEAD（应 == head_sha_at_create）
    git_commit_sha VARCHAR(40),                 -- 执行后 commit（无 diff 时为 NULL）
    git_changed_files TEXT,                     -- JSON array
    
    -- 审核
    reviewed_at DATETIME,
    review_reason VARCHAR(512),
    
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,              -- 创建时间 + 15 分钟
    deleted TINYINT DEFAULT 0,
    
    INDEX idx_request (request_id),
    INDEX idx_session (session_key, status),
    INDEX idx_status_expires (status, expires_at)
);
```

**状态机**：

```
                    ┌─────────────┐
                    │   PENDING   │
                    └──────┬──────┘
              ┌────────────┼────────────────┬──────────┐
              ▼            ▼                ▼          ▼
        confirm()    reject()       cancel()     超时(15分)
              │            │                │          │
              ▼            ▼                ▼          ▼
        APPROVED      REJECTED       CANCELLED    EXPIRED
              │
        execute()
              │
              ▼
        EXECUTING ─────┬───→ EXECUTED  (成功)
                       └───→ FAILED    (失败，不重试，需新建 proposal)
```

confirm 接口实现：

```java
@Transactional
public void confirm(String proposalId, String reason) {
    AgentActionProposal proposal = repository.findByProposalIdForUpdate(proposalId);
    if (proposal == null) throw new BusinessException(40404, "proposal 不存在");
    if (proposal.status() != PENDING) throw new BusinessException(40409, "状态非法");
    if (proposal.expiresAt().isBefore(now())) throw new BusinessException(40410, "已过期");
    
    // 事务内一气呵成：PENDING → APPROVED → EXECUTING
    boolean ok1 = repository.updateStatusOptimistic(
        proposalId, PENDING, APPROVED, proposal.version(), reason);
    if (!ok1) throw new BusinessException(40409, "状态变更失败");
    
    boolean ok2 = repository.updateStatusOptimistic(
        proposalId, APPROVED, EXECUTING, proposal.version() + 1, null);
    if (!ok2) throw new BusinessException(40409, "进入执行状态失败");
    
    // 不变量 16：事件名反映状态已是 EXECUTING（不是 ApprovedEvent）
    publisher.publishEvent(new ProposalExecutionRequestedEvent(proposalId));
}

// 事务提交后异步触发执行
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onProposalExecutionRequested(ProposalExecutionRequestedEvent event) {
    proposalExecutor.executeAsync(event.proposalId());
}
```

**Stuck 状态清理任务**（不变量 13）：

```java
@Scheduled(fixedDelay = 60_000)
public void cleanupStuckProposals() {
    // PENDING 超时 → EXPIRED
    repository.expirePending(PENDING, EXPIRED, now());
    
    // EXECUTING 卡死 → FAILED（不自动重放，避免部分落盘后重复写）
    repository.markStuckExecutingAsFailed(
        Duration.ofMinutes(10), 
        "execution interrupted or timeout");
}
```

**注意**：APPROVED 在事务内是瞬时状态，不需要单独清理。如果出现 stuck-APPROVED，说明 confirm 事务内的第二次 update（APPROVED→EXECUTING）失败但事务没回滚，这是数据一致性 bug，应当在测试里发现而不是靠定时任务兜底。

### 改造点 5：WorkspaceGitGuard

**核心策略**：
- 不无脑 stash
- 不全局 rollback
- workspace 可以 dirty，但 targetPaths 不能 dirty
- 只 add targetPaths，只 rollback targetPaths

**proposal 创建时**（在 SnapshotService.capture 内）：

```java
public ToolInvocationSnapshot capture(String toolName, String toolsetId, Object[] args, String riskLevel) {
    String canonicalJson = canonicalize(args);
    String argsHash = sha256(toolName + "\n" + toolsetId + "\n" + canonicalJson);
    
    // 解析并规范化 targetPaths（统一为 repo-relative normalized path）
    List<String> targetPaths = parseTargetPaths(toolName, args).stream()
        .map(p -> pathNormalizer.normalizeRepoPath(workspaceRoot, p))
        .toList();
    
    // 记录 baseline HEAD（不变量 10）
    String headShaAtCreate = git.headSha();
    
    // 检查 dirty 与 targetPaths 的交集（路径已规范化，不变量 12）
    Set<String> dirtyFiles = git.statusNameOnly().stream()
        .map(p -> pathNormalizer.normalizeRepoPath(workspaceRoot, p))
        .collect(Collectors.toSet());
    Set<String> intersection = dirtyFiles.stream()
        .filter(targetPaths::contains)
        .collect(Collectors.toSet());
    
    if (!intersection.isEmpty()) {
        // 拒绝创建可执行 proposal
        throw new BusinessException(40901, 
            "目标文件已有未提交改动，请先 commit/stash/清理后再执行: " + intersection);
    }
    
    return new ToolInvocationSnapshot(
        toolName, toolsetId, canonicalJson, argsHash,
        riskLevel, targetPaths,
        previewSummary(toolName, args, targetPaths),
        !dirtyFiles.isEmpty(), dirtyFiles,
        headShaAtCreate
    );
}
```

**执行时**：

```java
public <T> T execute(AgentActionProposal proposal, Callable<T> toolExecution) {
    String currentSha = git.headSha();
    
    // 不变量 10：HEAD 必须一致，否则 baseline 失效
    if (!currentSha.equals(proposal.gitHeadShaAtCreate())) {
        throw new SecurityException(
            "工作区 HEAD 已变化（baseline=%s, current=%s），proposal 失效，请重新生成"
                .formatted(proposal.gitHeadShaAtCreate(), currentSha));
    }
    
    // 路径规范化（不变量 12）
    Set<String> currentDirty = git.statusNameOnly().stream()
        .map(p -> pathNormalizer.normalizeRepoPath(workspaceRoot, p))
        .collect(Collectors.toSet());
    Set<String> targetPathsSet = new HashSet<>(proposal.targetPaths());
    
    // baseline 二次校验：targetPaths 仍必须 clean
    Set<String> targetPathsDirty = currentDirty.stream()
        .filter(targetPathsSet::contains)
        .collect(Collectors.toSet());
    if (!targetPathsDirty.isEmpty()) {
        throw new SecurityException("targetPaths 在 proposal 创建后变 dirty，baseline 失效: " + targetPathsDirty);
    }
    
    proposal.recordBaseline(currentSha);
    
    try {
        T result = toolExecution.call();
        
        // 计算 newlyChangedFiles
        Set<String> afterDirty = git.statusNameOnly().stream()
            .map(p -> pathNormalizer.normalizeRepoPath(workspaceRoot, p))
            .collect(Collectors.toSet());
        Set<String> newlyChanged = Sets.difference(afterDirty, currentDirty).immutableCopy();
        
        // 不变量 7：newlyChanged ⊆ targetPaths
        Set<String> outOfScope = Sets.difference(newlyChanged, targetPathsSet).immutableCopy();
        
        if (!outOfScope.isEmpty()) {
            log.error("工具改动超出 targetPaths: violations={}", outOfScope);
            // 不变量 15：精确清理，区分 tracked/untracked，不碰用户原 dirty
            rollbackTargetPaths(currentSha, proposal.targetPaths());
            cleanOutOfScope(currentSha, outOfScope);
            throw new SecurityException("工具改动超出授权范围: " + outOfScope);
        }
        
        // 不变量 14：处理无实际 diff 的情况
        if (newlyChanged.isEmpty()) {
            proposal.recordNoOpExecution();
            return result;
        }
        
        // 只 add targetPaths，commit
        git.add(proposal.targetPaths());
        String commitSha = git.commit(buildCommitMessage(proposal));
        proposal.recordCommit(commitSha, new ArrayList<>(newlyChanged));
        
        return result;
    } catch (Exception ex) {
        rollbackTargetPaths(currentSha, proposal.targetPaths());
        proposal.recordFailure(ex.getMessage());
        throw ex;
    }
}

private void rollbackTargetPaths(String sha, List<String> targetPaths) {
    for (String path : targetPaths) {
        if (git.isTracked(path)) {
            git.checkout(sha, path);   // tracked 文件用 checkout 恢复
        } else {
            git.deleteFile(path);      // untracked 文件直接删
        }
    }
}

private void cleanOutOfScope(String sha, Set<String> outOfScope) {
    // 不变量 15：只清理本次工具产生的越界文件，不碰用户原 dirty
    for (String path : outOfScope) {
        if (git.isTracked(path)) {
            git.checkout(sha, path);
        } else {
            git.deleteFile(path);
        }
    }
}

private String buildCommitMessage(AgentActionProposal p) {
    return """
            [agent-write] %s
            
            proposalId: %s
            requestId: %s
            runId: %s
            tool: %s
            user: %s
            argsHash: %s
            baselineSha: %s
            targetPaths: %s
            """.formatted(
                p.previewSummary(),
                p.proposalId(), p.requestId(), p.runId(),
                p.toolName(), p.userId(),
                p.argumentsHash(), p.gitBaselineSha(),
                String.join(", ", p.targetPaths())
            );
}
```

**PathNormalizer 工具**（独立组件，所有路径校验必须经过它）：

```java
@Component
public class PathNormalizer {
    public String normalizeRepoPath(Path workspaceRoot, String rawPath) {
        Path normalized = workspaceRoot.resolve(rawPath).normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException("path escapes workspace: " + rawPath);
        }
        return workspaceRoot.relativize(normalized).toString().replace("\\", "/");
    }
}
```

### 改造点 6：SSE action_required 协议 + 不持锁

**ChatServiceImpl 改造**：

```java
private void executeStream(ChatRequest request, ...) {
    ChatContext context = null;
    try {
        context = chatContextFactory.build(request, true);
        AgentEngine engine = engineSelector.select(context);
        // ... 正常流程
    } catch (PendingApprovalException ex) {
        // 1. 从 proposal 拿 runId（不依赖 catch 外的 context）
        AgentActionProposal proposal = proposalService.findByProposalId(ex.proposalId());
        String runId = proposal.runId();
        
        // 2. 状态变更
        agentRunService.markWaitingConfirmation(runId, proposal.proposalId());
        
        // 3. 发 SSE
        sseEventBridge.sendActionRequired(emitter, proposal);
        sseEventBridge.completeEmitter(emitter);
        
        // 4. 立即释放运行时资源
        // （session lock 已在外层 finally 释放，emitter 已 complete）
    }
}
```

**SSE 事件结构**：

```typescript
interface ActionRequiredEvent {
    type: "action_required";
    proposalId: string;
    requestId: string;
    runId: string;
    toolName: string;
    riskLevel: "write" | "dangerous" | "side_effect";
    targetPaths: string[];
    previewSummary: string;
    workspaceDirty: boolean;
    expiresAt: string;
}
```

**confirm 后的 resume**：confirm API 是独立的 HTTP 请求，不复用原 SSE 连接。前端确认后通过新的 SSE 流（或轮询）拿执行结果。

### 改造点 7：confirm/reject/resume API

**新增端点**：

```
POST /api/proposals/{proposalId}/confirm
  body: { reason?: string }
  response: { proposalId, status }

POST /api/proposals/{proposalId}/reject  
  body: { reason: string }
  response: { proposalId, status }

GET /api/proposals/{proposalId}
  response: AgentActionProposal

GET /api/proposals?sessionKey=xxx&status=PENDING
  response: AgentActionProposal[]
```

**ToolInvoker 接口**（封装反射，不变量 11：必须经过 Aspect）：

```java
public interface ToolInvoker {
    /**
     * 执行工具调用。实现必须保证调用路径会被 ToolRuntimeAspect 拦截，
     * 使 confirm resume 路径能再次触发 Aspect 的二次校验。
     * 不能直接反射调用 Method 绕过 Spring proxy。
     */
    Object invoke(String toolName, String argumentsCanonicalJson);
}

@Component
public class SpringToolInvoker implements ToolInvoker {
    private final ToolCallbackResolver resolver;
    private final ObjectMapper mapper;
    
    @Override
    public Object invoke(String toolName, String argumentsCanonicalJson) {
        ToolCallback callback = resolver.resolve(toolName);
        if (callback == null) throw new IllegalArgumentException("toolName 不存在: " + toolName);
        return callback.call(argumentsCanonicalJson);
    }
}
```

> ⚠️ **CI 测试硬约束**：必须有一个集成测试验证 `confirm resume → ToolInvoker.invoke → ToolRuntimeAspect 命中`。
> 如果 Spring AI 的 `ToolCallback.call()` 内部直接反射调用 method 绕过 Spring proxy，
> 必须改成显式经过 Aspect 的实现（例如把 Aspect 抽成可注入的 `ToolExecutionGuard` 组件）。
> **此约束不通过则整个 P0 安全模型失效。**

**ProposalExecutionService**：

```java
@Component
public class ProposalExecutionService {
    
    @Async("proposalExecutor")
    public void executeAsync(String proposalId) {
        AgentActionProposal proposal = repository.findByProposalId(proposalId);
        if (proposal.status() != EXECUTING) {
            log.warn("proposal {} 状态非 EXECUTING，跳过", proposalId);
            return;
        }
        
        ApprovedProposalContext ctx = ApprovedProposalContext.from(proposal);
        ToolExecutionContextHolder.setApprovedProposal(ctx);
        
        try {
            // 重新调用工具——会再次进入 ToolRuntimeAspect
            // Aspect 看到 ctx 存在，做二次校验，通过后走 GitGuard
            Object result = toolInvoker.invoke(proposal.toolName(), proposal.argumentsCanonicalJson());
            
            // 工具执行成功的状态变更已经在 GitGuard 内完成
            sseEventBridge.broadcastProposalExecuted(proposal);
        } catch (Exception ex) {
            repository.markFailed(proposalId, ex.getMessage());
            sseEventBridge.broadcastProposalFailed(proposal, ex);
        } finally {
            ToolExecutionContextHolder.clearApprovedProposal();
        }
    }
}
```

---

## 6. 测试矩阵

| 用例 | 预期结果 |
|------|---------|
| read 工具调用 | 直接执行，不创建 proposal |
| write 工具未确认 | 抛 PendingApprovalException，proposal=PENDING，不落盘 |
| write 工具确认后 | proposal=EXECUTED，文件落盘，git commit 生成 |
| confirm 后 args 不一致（绕过场景） | Aspect 校验失败抛 SecurityException，proposal=FAILED |
| reject | proposal=REJECTED，不执行，不可再次 confirm |
| 超时未确认 | proposal=EXPIRED |
| 重复 confirm | 第二次返回 40409，不重复执行 |
| 并发 confirm（两个用户） | 乐观锁，只有一个成功 |
| dirty workspace + targetPaths clean | proposal 正常创建，元数据标记 dirty=true |
| dirty workspace + targetPaths dirty | 拒绝创建 proposal，返回 40901 |
| 工具改了 targetPaths 之外的文件 | rollback、抛 SecurityException、proposal=FAILED |
| 5 个引擎渲染 prompt | 每个都包含 ContextInjection 的 observePrompt 内容 |
| 工具执行抛异常 | rollback 到 baseline，proposal=FAILED，原 dirty 文件不动 |
| Run waiting confirmation 后服务重启 | proposal 还在 DB，confirm 后能 resume |
| **Confirm resume 路径必经 Aspect**（不变量 11） | 集成测试验证 ToolInvoker.invoke 触发 ToolRuntimeAspect.guard |
| **HEAD 在确认期间被 commit**（不变量 10） | 执行前检测到 HEAD 不一致，抛 SecurityException，proposal=FAILED |
| **写工具无 diff**（不变量 14） | proposal=EXECUTED，git_commit_sha=NULL，无报错 |
| **outOfScope 包含 untracked 文件**（不变量 15） | 用 deleteFile 清理而不是 git checkout，不抛错 |
| **路径形式不一致**（不变量 12） | targetPaths 传 `./src/A.java`，dirtyFiles 是 `src/A.java`，规范化后正确识别为同一文件 |
| **stuck EXECUTING 超时**（不变量 13） | 标记 FAILED，不重放，不重复写文件 |

---

## 7. 风险

1. **Git 操作的副作用**：rollback 用 `git checkout sha path` 而不是 `git stash`。如果用户在工具执行前刚好 stage 了某个 targetPath 的文件，rollback 会丢失 stage——但 proposal 创建时已经检查 dirtyFiles ∩ targetPaths 为空，不会进入这种状态。
2. **Aspect 性能**：每次 write 工具调用都查一次 DB。可接受——write 调用频率本来就低，且事务可见性保证比 cache 更重要。
3. **状态机死锁**：EXECUTING 后服务宕机不知道工具是否部分落盘——按不变量 13 标记 FAILED 不重放，用户重新生成 proposal。APPROVED 在事务内是瞬时状态，不存在 stuck-APPROVED；如果出现，是数据一致性 bug，应当在测试里发现。
4. **SSE 重连**：用户刷新页面后原 SSE 连接断开。confirm 是独立请求，不依赖原 SSE。前端通过 GET /api/proposals?sessionKey&status=PENDING 拉待确认列表。
5. **ToolInvoker 反射失败**：Spring AI 的 ToolCallback 解析机制可能不稳。封装在 ToolInvoker 接口后面，便于换实现。**但实现必须经过 Aspect**（不变量 11），否则二次校验失效，整个安全模型崩塌。

---

## 8. 不在本轮的事

为避免范围漂移，明确推迟到后续轮次：

| 项目 | 原因 | 预计轮次 |
|------|------|---------|
| 合并 6 引擎到 2-3 个 | 范围太大，本轮先让所有引擎统一消费上下文 | P1 |
| 完整 PolicyService（统一 ToolPermission/Risk/Workspace/Proposal/Orchestrator） | 散在 5 处，统一是大重构 | P1 |
| ToolOrchestrator 按 intent×risk 严格限制工具集（方案 B） | 不是最后防线，本轮加最小 deny rule 即可 | P1 |
| 减少一次请求的 LLM 调用次数 | 性能问题，不影响安全 | P2 |
| 接 Obsidian / Wiki.js 作为 KnowledgeSource | 与本轮无关 | P2 |
| Memory tab 接真实数据 | UI 完整性问题 | P3 |

---

## 9. 验收标准

| 标准 | 验证方式 |
|------|---------|
| 写操作必经确认 | 自动测试：调用 workspaceWriteFile 不预先创建 proposal → 失败 |
| 写后可回滚 | 自动测试：写完后 `git log -1` 能拿到 commit_sha；`git revert` 能恢复 |
| 干路上下文注入 | 自动测试：5 个引擎的渲染 prompt 都包含 observePrompt |
| 不阻塞线程 | 代码 review：catch PendingApprovalException 后立即 completeEmitter，不 sleep/wait |
| **ToolInvoker 必经 Aspect**（不变量 11） | 集成测试：confirm resume 路径调用 ToolInvoker.invoke 时 ToolRuntimeAspect.guard 被命中 |
| **HEAD 一致性守护**（不变量 10） | 自动测试：proposal 创建后手动 commit 改变 HEAD，confirm 后执行被拒绝 |
| **路径规范化**（不变量 12） | 自动测试：targetPaths 用 `./src/A.java`，dirtyFiles 是绝对路径，规范化后能正确求交集 |
| 测试覆盖 | mvn test 通过；新增至少 20 个测试用例覆盖测试矩阵 |
| 前端验收 | npm run build 通过；前端能渲染 action_required 事件并提供 confirm/reject 按钮 |

---

## 10. 决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 确认机制位置 | ToolRuntimeAspect 最终门禁 | 最贴近真实风险，不依赖意图判断准确性 |
| 等待确认期间 | 不阻塞，事件化 + 持久化 | 避免线程锁、SSE 资源跨用户操作 |
| Snapshot 与 Proposal 关系 | 一表合一 | 避免双源、字段不一致 |
| argsHash 计算 | toolName + toolsetId + canonicalArgs | 确认语义完整 |
| Aspect 是否发 SSE | 不发，只抛领域异常 | 分层清晰，Aspect 不知道 SSE |
| Aspect 信任源 | 只信后端 ExecutionContext | 不被前端伪造 token 欺骗 |
| Aspect 准入状态 | 必须是 EXECUTING（不是 APPROVED） | EXECUTING 才是"执行器拿到执行权" |
| ContextInjection 字段 | 4 字段结构化（observe/policy/pendingProposal/metadata） | 给 P1 留口子 |
| 引擎接口 | 不动 AgentEngine，只改 prompt 拼接 | 范围收敛，不影响测试 |
| Git 策略 | 不 stash，只 add/rollback targetPaths | 不动用户现场 |
| dirty workspace | targetPaths ∩ dirtyFiles 必须为空 | 不混合用户改动和 agent 改动 |
| FAILED 是否可重试 | 不重试原 proposal，重试创建新 proposal | 一次性授权语义 |
| Confirm 后是否重跑模型 | 不重跑，只重放 snapshot | 防止模型第二次生成不同参数 |
| HEAD 在确认期间变化 | 拒绝执行，要求重新生成 proposal | baseline 失效，确认语义改变 |
| ToolInvoker 是否可绕过 Aspect | 必须经过 Aspect，CI 测试守护 | 绕过则二次校验失效，安全模型崩溃 |
| 路径比较 | 全部走 PathNormalizer 规范化 | 防止字符串形式差异绕过校验 |
| Stuck EXECUTING 处理 | 标记 FAILED，不自动重放 | 不知道工具是否部分落盘，重放可能重复写 |
| Stuck APPROVED 处理 | 不单独清理，靠测试发现一致性 bug | APPROVED 在事务内是瞬时状态，不该长期存在 |
| 写工具无 diff | EXECUTED，commit_sha=NULL，不强制 --allow-empty | 空 commit 对 rollback 无意义 |
| outOfScope 文件清理 | 区分 tracked/untracked（checkout vs delete） | tracked 用 checkout 恢复，untracked 用 delete |
| Confirm 后事件命名 | ProposalExecutionRequestedEvent | 反映 EXECUTING 而不是 APPROVED |
