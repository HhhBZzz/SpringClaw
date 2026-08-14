# 事件地基统一与共享循环核抽取 — 设计 Spec

- 日期：2026-08-14
- 状态：已批准（方案 C，用户于 brainstorming 中拍板：先 Phase 1 事件地基，再 Phase 2 循环核，连续执行不停顿）
- 参照系：deepseek-harness（DeepSeek 官方 2026-08-13 开源，"Everything is a Plugin"，事件溯源会话 + 极小循环核）与 SpringClaw 的对照差距，见长期记忆「架构参照系(2026-08-14)」。

## 0. 问题陈述

SpringClaw 的 canonical run 事件体系（`runtime/contract` + `runtime/lifecycle` + `runtime/bridge`）骨架正确（唯一 emit 入口 RunCoordinator、乐观锁状态机、append-only 事件日志），但存在三组结构性缺陷：

**D1 — 事件语义残缺：**

1. `RUN_COMPLETED`（wire `run.completed`）有枚举、有 `RunCoordinator.completed()` bridge 方法，但**全库零生产调用方**——所有成功 run 的终态都是 `RunResultProjector.adaptDegraded()` 强制打上的 `RUN_DEGRADED` + reason `LEGACY_UNVERIFIED_RESULT`。语义上"系统从不承认成功"。
2. 无任何 turn/step 边界事件。一次 5 引擎 run 内部的循环步进（ReAct 第 N 步、Reflexion 第 N 轮反思、PlanExecute 的 replan）在事件流上不可见，step 序号仅存于 `agent_run_step` 第三套投影表。违反"Model-visible ⟺ Logged"原则的孪生原则：**发生过 ⟺ 可回放**。
3. `VERIFICATION_COMPLETED`（wire `verification.completed`）在进入 `VERIFYING` 状态时发射（`RunCoordinator.verifying()`），名实不符。
4. 所有 observation 事件（`model.called`/`tool.started`/`tool.succeeded`/`tool.failed`/`memory.extracted`/`reflect.completed`）payload 恒为 `"{}"`、`durationMs=0`、`causationId=null`——事件流存在但不可用。

**D2 — 循环核重复：** 5 个范式引擎（ReAct 556 / Reflexion 619 / PlanExecute 778 / OparLoop 569 / AutonomousLoop 643 行）共 3165 行中有 ~990 行（31%）是逐字或近似重复的骨架段（A stream 壳 / C 工具前奏 / D 模型调用 / G 假完成守护 / H max-steps / I catch 降级 / K isSafeToRetry / L releaseLockOnce / M reportResult / N resolveFinalAnswer / O observePrompt…20 个重复段）。新范式 = 复制粘贴新引擎 = 再复制一遍骨架。

**D3 — 配置半持久态：** `springclaw.persistence.db-enabled`（默认 false）与 `springclaw.runtime.lifecycle.store`（默认 memory）两个独立开关可组合出"业务表走 MySQL 但 run 事件走内存"的半持久状态，无一致性校验。

## 1. 目标与非目标

### 目标

- G1：成功 run 能以 `run.completed` 正常终态，`run.degraded` 回归其本义（降级但仍有产出）。
- G2：turn/step 边界事件进入 canonical 事件流，一次 run 可从事件日志完整回放循环结构。
- G3：observation 事件携带可用的 payload（provider/model、工具名、耗时、因果链）。
- G4：共享循环核吃掉引擎骨架重复，7 个引擎瘦身为策略；**循环核成为 turn/step 边界事件的唯一发射点**（Phase 1 与 Phase 2 的衔接点）。
- G5：db-enabled / lifecycle.store 组合一致性 fail-fast。
- G6：外部契约零破坏（见 §4 兼容性约束清单）。

### 非目标

- 不迁移 `message_event` 自由字符串体系（chat history、[REFLECT] 前缀、audit 前缀解析等 legacy 消费者保持原样；双体系归一另立 spec）。
- 不动 `AgentRuntimeEngine`（953 行，旧一代 OPAR 实现，与 OparLoopEngine 路由互补互斥）与 `SimplifiedOparEngine`/`BasicStreamEngine`/`ModelLedStreamEngine`——Phase 2 只迁移 5 个循环引擎。AgentRuntimeEngine 处置（收敛或退役）留待事件地基稳定后独立评估。
- 不引入插件框架（Cordis 式）——Spring 的 `@Component` + 接口已够用，引入新框架违背项目技术栈。
- 不做前端可视化增强（turn/step 事件先落库，前端消费留待范式可视化里程碑）。

## 2. Phase 1 — 事件地基

### 2.1 新增事件类型（RunEventType）

在既有 21 枚举后追加 4 个，wire 名沿用现有命名风格：

```java
TURN_STARTED("turn.started"),
TURN_COMPLETED("turn.completed"),
STEP_STARTED("step.started"),
STEP_COMPLETED("step.completed"),
```

语义定义（对照 dsh 的事件溯源会话）：

- **turn**：一次用户输入 → 系统产出最终回答的完整处理（非流式一次 chat 请求 = 1 turn；流式一次 SSE 会话 = 1 turn）。`run.created` 与 `turn.started` 在 MVP 阶段一一对应（run 是 turn 的持久化投影），后续 webhook/task 路径的 turn 语义再细化。
- **step**：turn 内引擎循环的一步（ReAct 的一次 Thought→Action→Observation、Reflexion 的一轮反思、PlanExecute 的一次 plan/execute/replan 迭代）。step 事件 payload 必含 `stepIndex`（从 0 递增）。

事件类型→发射方式：这 4 个均为 **observation**（`RunCoordinator.appendObservation` 路径，不改状态机状态，终态后仍可追加/之前可发射）——不动 `RunStatus` 状态机与 `RunTransitionPolicy`，状态机迁移风险为零。

**发射点策略（两阶段走）：**
- Phase 1 内：`RunLifecycleObserver` 新增 `turnStarted`/`turnCompleted`（由 ChatServiceImpl 在 run 接受后/结果返回前调用）+ `stepStarted`/`stepCompleted`（由引擎在每步循环边界调用）。此时引擎尚未迁移到循环核，5 个引擎各加 2-4 行 emit 调用（见 §2.4 渐进策略）。
- Phase 2 完成后：step 事件发射随循环步进一起沉入 `AgentLoopKernel`（引擎不再各自手写）。

### 2.2 修复 run.completed 缺失 — CompletionVerifier SPI

核心问题：谁有资格宣布"成功"？当前答案是无人（所以全部 degraded）。dsh 的答案是"验证世界而非自我报告"。

**新增 SPI：**

```java
public interface CompletionVerifier {
    /** 是否愿意对这类 run 做完成判定 */
    boolean supports(ChatContext context, ChatExecutionResult result, String answer);
    /** 判定：COMPLETE（真成功）/ DEGRADE（降级但产出可用）*/
    CompletionVerdict verify(ChatContext context, ChatExecutionResult result, String answer);
}
record CompletionVerdict(CompletionDecision.Outcome outcome, String reasonCode, String summary) {}
```

**默认实现（MVP 判据，可叠加）：**
- `TrustedModelVerifier`：`ChatExecutionResult.modelEnabled == true` 且 answer 非空非占位（非"模型不可用"降级文案）→ COMPLETE。
- 走了 fallback 本地技能路径（`modelEnabled == false`）→ DEGRADE，reason `MODEL_UNAVAILABLE_LOCAL_FALLBACK`。
- 完全无 answer → 维持现状走 `RunCoordinator.failed`。

**接线：** `RunLifecycleObserver.resultReturned()` 改为依次询问 verifier 链（Spring 注入 `List<CompletionVerifier>`，首个 `supports` 的判定生效）：

```
resultReturned:
  bridge.verificationStarted(runId, at)                    // 保持现有 VERIFYING 进入
  verdict = verifierChain.verdict(context, result, answer) // 新增
  COMPLETE → bridge.completed(runId, decision(COMPLETE, ...), result, at)
  DEGRADE  → bridge.degraded(runId, decision(DEGRADE, reason, ...), result, at)
  FAIL     → bridge.failed(...)
```

**验**：既有 smoke 断言 `containsExactly(RUN_CREATED, CONTEXT_READY, DECISION_MADE, STRATEGY_STARTED, VERIFICATION_COMPLETED, RUN_DEGRADED)` 需同步为 `..., run.completed`。`LEGACY_UNVERIFIED_RESULT` reason 淘汰，`RunResultProjector.adaptDegraded` 重构为 `adaptTerminal(verdict)`。

### 2.3 VERIFICATION_COMPLETED 名实不符修复 + observation payload 富化

**a) wire 名修复（有破坏性，需迁移）：** `verification.completed` → `verification.started`，枚举名 `VERIFICATION_COMPLETED` → `VERIFICATION_STARTED`。

风险与对策：`RunEvent.fromWireName` 对未知 wire 名抛 `IllegalArgumentException`。MySqlRunLifecycleStore 读存量记录时按 wire 名反序列化——**改 wire 名会炸存量数据读取**。对策：`fromWireName` 增加遗留别名映射（`verification.completed` → `VERIFICATION_STARTED`），保证旧记录可读。这样写入侧全部用新名，读取侧兼容旧名。

**b) payload 富化 — observation emit API 升级：**

```java
public RunEvent modelCalled(String runId, String providerId, String model, Instant at);
public RunEvent toolStarted(String runId, String toolName, String toolsetId, Instant at);
public RunEvent toolSucceeded(String runId, String toolName, long durationMs, String outcomeSummary, Instant at);
public RunEvent toolFailed(String runId, String toolName, String errorCode, Instant at);
public RunEvent turnStarted(String runId, String responseMode, Instant at);
public RunEvent turnCompleted(String runId, String outcome, long durationMs, Instant at);
public RunEvent stepStarted(String runId, int stepIndex, String stepKind, Instant at);
public RunEvent stepCompleted(String runId, int stepIndex, String stepKind, String outcome, long durationMs, Instant at);
```

payload 为 JSON：`{"providerId":"primary","model":"deepseek-chat"}`、`{"stepIndex":2,"stepKind":"replan"}`。payloadSchema 区分：结构化 payload 用 `springclaw.runtime.observation.v1`，保持 lifecycle.v1 只用于无 payload 事件（含因果链：有 step 边界的 turn，`step.started` 的 causationId = `turn.started` 的 eventId）。

发射点：`tool.started/succeeded/failed` 的真实发射点在 `ToolRuntimeAspect`/工具执行边界（现有调用已存在，只升参数）；`model.called` 在 `ModelCallExecutor`（现有调用点已存在）。Phase 1 只升级既有调用点的参数，不新增调用点。

**c) durationMs 归属：** 当前 event().durationMs 恒 0。规则：observation 的 durationMs 由发射者传入（step/turn 完成事件必传真实值；model/tool 完成事件传调用耗时）；状态机事件保持 0（状态迁移本身无耗时语义，其耗时由相邻事件时间差可得）。

### 2.4 渐进策略与引擎 emit 埋点（Phase 1 内最小侵入）

5 个循环引擎各自补 2-4 行 emit 调用是必要的过渡态（Phase 2 会把这些行沉入内核）。为避免过渡期 5 处重复，提供薄助手：

```java
@Component
public class StepEventEmitter {
    public <T> T step(String runId, int stepIndex, String stepKind, Supplier<T> body) {
        coordinator.stepStarted(runId, stepIndex, stepKind, now);
        long t0 = System.nanoTime();
        try {
            T result = body.get();
            coordinator.stepCompleted(runId, stepIndex, stepKind, outcome(result), elapsed, now);
            return result;
            // 异常路径: stepCompleted(outcome="failed") 后重抛, 由引擎既有 catch 降级
        } catch (Exception e) {
            coordinator.stepCompleted(runId, stepIndex, stepKind, "failed", elapsed, now);
            throw e;
            // 重抛保证引擎既有 catch 降级/失败路径不变
        }
    }
}
```

引擎侧一行包裹：`var out = stepEvents.step(rid, i, "act", () -> callModel(...))`。OparLoopEngine（每步 Plan+Act 双调用）记一步；ReAct 一轮 Action 为一步；Reflexion 一轮反思为一步；PlanExecute 一次 plan/execute/replan 迭代为一步；AutonomousLoop 一次子任务执行为一步。

### 2.5 双开关一致性 fail-fast

`RuntimeLifecycleStoreConfig` 增加组合校验：`db-enabled=true && lifecycle.store != mysql` 时启动失败（fail-fast），错误信息给出纠正指引。`db-enabled=false && store=mysql` 合法（只跑 canonical 事件持久层）。

```java
@Validated @Configuration
public class RuntimeLifecycleStoreConfig {
    // mysql bean 条件不变; 新增:
    @Bean
    static RuntimeStoreConsistencyValidator runtimeStoreConsistencyValidator() {
        // SmartInitializingSingleton 阶段读取两开关,非法组合抛 BeanCreationException
    }
}
```

实现细节：用 `Environment` 注入的独立校验类（`@Component` + `ApplicationRunner` 之前抛异常 = Spring 启动失败），测试用纯 JUnit 直测校验逻辑 + @SpringBootTest 冒烟（非法组合 expected=退出码非零/上下文启动失败）。

### 2.6 持久化层

`MySqlRunLifecycleStore` / `InMemoryRunLifecycleStore` 无需结构变更（payload 列已是 String，新事件类型只是新行）。`runtime_run_event` 表若对 `event_type` 有枚举 CHECK 约束需同步迁移（启动时 schema 校验确认，若无约束则零 DDL）。`agent_run_step` 投影表本期不动。

### 2.7 Phase 1 测试矩阵

| 层 | 测试 |
|---|---|
| 契约 | `RunEventTypeTest`：4 新 wire 名、`fromWireName` 别名（verification.completed→VERIFICATION_STARTED）、全部 wire 名唯一 |
| 协调器 | `RunCoordinatorTest` 增：turn/step observation 在 RUNNING/VERIFYING/终态后均可追加；payload JSON 结构断言 |
| 验证器 | `CompletionVerifierTest`：modelEnabled=true+真实 answer→COMPLETE；fallback→DEGRADE(MODEL_UNAVAILABLE_LOCAL_FALLBACK)；空 answer→FAIL 路径 |
| 桥 | `RunLifecycleObserverTest`：resultReturned 走 verifier 链后 completed/degraded 分流；verificationStarted 事件名 |
| 引擎埋点 | 每引擎 1 个测试：stepStarted/stepCompleted 事件序列存在且 stepIndex 递增 |
| 集成 | CanonicalSmoke 断言更新（run.completed 终态）；payload 富化断言（model.called 的 payload 含 providerId） |
| 配置 | 双开关非法组合启动失败；合法组合各一例 |
| 全量回归 | 1091 测试基线全绿 |

## 3. Phase 2 — 共享循环核 AgentLoopKernel

### 3.1 设计原则（来自 dsh 对照结论）

- **范式 = 扩展点上的组合，不是引擎类。** 循环核掌握骨架（步骤推进、事件发射、模型调用、工具挂载、降级、终态），范式引擎只表达差异点。
- **骨架下沉、语义上浮。** A/C/D/G/H/I/K/L/M/N/O 等 20 个重复段沉入内核；ReAct 的文本协议、Reflexion 的反思器、PlanExecute 的 replan 循环、AutonomousLoop 的子任务标记是策略语义，留在策略类。
- **不引新框架。** Spring `@Component` + 接口 + record 组合即可表达全部扩展点。

### 3.2 AgentLoopKernel 结构（目标 ~400 行）

```java
@Component
public class AgentLoopKernel {
    // 依赖: ModelCallExecutor, RunCoordinator(经 RunLifecycleObserver), ToolInvocationTracker,
    //        StepEventEmitter(Phase 1 产物), ObjectMapper

    /** 单次 LLM 调用的完整包装: 前奏(C段) + 调用(D段) + 事件(payload富化) + 假完成守护(G段) + isSafeToRetry(K段) */
    public KernelResult callModel(KernelCall call) { ... }

    /** 步进循环骨架: step边界事件 + max-steps兜底(H段) + 每步策略回调 */
    public <S> KernelResult runLoop(LoopSpec<S> spec, LoopContext ctx) { ... }

    /** 降级与终态: 假完成文案生成 + reportResult(M段) + resolveFinalAnswer(N段) */
    public String finalize(LoopContext ctx, KernelResult last, FinalizeSpec spec) { ... }
}
```

策略接口（内核回调，非引擎实现）：

```java
public interface LoopSpec<S> {
    int maxSteps();                       // H段
    String stepKind();                    // step事件 payload
    S initState(ChatContext ctx);         // 循环状态(Reflexion的memory累积等)
    StepAction<S> nextStep(S state, int stepIndex);  // 步语义
    boolean isTerminal(S state, KernelResult last);  // 假完成守护的范式版
    String composeAnswer(S state, List<KernelResult> steps);  // N段
}
```

- `runLoop` 骨架：step.started → nextStep（内部经 callModel：tools 前奏+模型调用+model.called 富化事件+假完成守护）→ step.completed → isTerminal 判定 → 循环或退出 → maxSteps 兜底。
- `callModel` 内部：C 段（工具选择+tracker+ToolExecutionContext）→ D 段（ModelCallExecutor 调用，BeanOutputConverter 变体 D' 由 `KernelCall` 参数化）→ G 段假完成守护（空回答/降级文案检测）→ K 段 isSafeToRetry。
- 降级三段（B 模型不可用降级 / I catch 降级 / M reportResult / L releaseLockOnce）在内核 `finalize` + 引擎 execute() 外壳保留（execute 的 try/catch 骨架仍在引擎，但调内核方法）。
- A 段（SSE 壳）：`StreamableAgentEngine.stream()` 签名不变，5 个流式引擎的 stream() 壳调用内核 `runLoop` 的流式变体或保持引擎自管（MVP：ReAct/Reflexion/PlanExecute 的 stream() 改调内核流式 runLoop；OparLoop 无 SSE；AutonomousLoop 视实际实现）。

### 3.3 迁移顺序（每步独立可测可提交）

按"重复度收益×语义简单度"排序，先易后难：

1. **ReActEngine**（556 行，差异点最清晰：文本协议 Action 行终止）— 首个试点，验证内核 API 形状。预计 556 → ~260 行策略。
2. **ReflexionEngine**（619 行，Reflector 二次调用+memory 累积是 `LoopSpec.initState/nextStep` 的试金石）。
3. **PlanExecuteEngine**（778 行，外层 replan 循环嵌套内层执行——嵌套循环映射为 `nextStep` 内部子步骤，step 事件记外层）。
4. **AutonomousLoopEngine**（643 行，母体引擎+TASK_COMPLETE 标记识别 → `isTerminal` 策略）。
5. **OparLoopEngine**（569 行，每步 Plan+Act 双调用 → `nextStep` 内两次 `callModel`；无 SSE；本地短路四件套经 `KernelCall` 参数开关）。
6. 收尾：KernelSpec/LoopSpec API 定稿（前 5 个引擎的经验回灌）、删引擎内残留死代码、全量回归。

每步验收：引擎单测全绿 + step 事件序列断言 + 行数统计（目标 5 引擎合计 ~1010-1240 策略行 + ~400 内核行）。

### 3.4 Phase 2 测试策略

- 内核自身：`AgentLoopKernelTest` 纯 JUnit（mock ModelCallExecutor/RunCoordinator）覆盖 callModel 的 G 段守护矩阵、runLoop 的 max-steps、finalize 三分支。
- 引擎迁移不改单测语义（既有引擎测试继续通过 = 行为不变的证明），新增 step 事件序列断言。
- CanonicalSmoke 端到端：多 step run 的事件序列含完整 turn.started → (step.started/step.completed)×N → turn.completed → run.completed 链。
- **回归线**：任何时刻 `mvn test` 必须全绿（1091+新增）。

### 3.5 不变量（Phase 2 全程保持）

- I1 外部 API 契约（§4 清单）零破坏。
- I2 `EngineSelector` 路由逻辑不变（引擎的 name/priority/supports/paradigm 四元组不变）。
- I3 事件序列前缀兼容：迁移后的引擎产生的 canonical 事件序列是迁移前的超集（只新增 turn/step 边界，不删除/重排既有事件）。
- I3 是保护既有 smoke 断言 `containsExactly` 的关键——**含 turn/step 事件后 containsExactly 会失败**，故 smoke 断言同步改为前缀/包含式断言（`containsSequence` 或提取 lifecycle 事件子序列）。

### 3.6 风险与回滚

| 飉险 | 对策 |
|---|---|
| 内核 API 在第 5 个引擎才暴露设计缺陷 | 顺序刻意先易后难；每引擎迁移是独立 commit，API 缺陷时仅回滚该引擎（revert 单 commit） |
| 引擎行为漂移（迁移引入语义变化） | 既有引擎单测是行为快照（不修改断言只迁移实现）；迁移前后事件序列 diff（I3 超集校验） |
| step 事件量膨胀（高频循环） | observation 不进状态机，追加成本低；InMemory 100 条上限保护 memory 模式；mysql 模式事件量与 agent_run_step 投影同级 |
| OparLoop 本地短路四件套难参数化 | 若第 5 步受阻，OparLoop 允许保留独立实现（它无 SSE、双调用结构确实特殊），Phase 2 目标收缩为 4/5 引擎收敛 |

### 3.7 Phase 2 交付物

- `service/agent/kernel/`（AgentLoopKernel + LoopSpec + KernelCall/KernelResult + StepEventEmitter 迁入）
- 5 引擎瘦身（合计 −1500~1800 行净减）
- 引擎迁移前后事件序列对照记录（docs/superpowers/specs/ 附录用 I3 超集校验输出）
- 全量回归绿

## 4. 兼容性约束清单（外部契约，零破坏）

| # | 契约 | 约束 |
|---|---|---|
| C1 | `/api/chat/history` | 依赖 `message_event` 的 CHAT eventType + `[REFLECT]` 前缀——本设计不动 message_event 写入路径 |
| C2 | audit 端点 | 解析 `tool=...` / `MEMORY_USAGE=...` content 前缀——不动 |
| C3 | SSE trace payload 字段 | `AgentRunTraceEvent` 结构（前端 AgentView timeline 消费）——toTraceEvent 透传 wireName，前端 `type` 是宽松 string 联合，**新增事件类型自动兼容**；不删不改既有字段 |
| C4 | `RunEventType.wireName` 既有 21 个 | **不可改名/删除**（存量 DB 记录按 wire 名反序列化，`fromWireName` 未知名抛异常）。唯一例外：verification.completed 按 §2.3a 加别名读取兼容 |
| C5 | SSE 事件流给前端的消息事件（chat/stream 路径） | 既有 trace/status/final 消息格式不变；turn/step 不进 SSE 推送（只进 canonical 事件日志）——避免前端未消费的推送噪声 |
| C6 | `agent_run_step` 投影表 | 本期不动，step 序号语义与新增 step 事件互不影响 |
| C7 | 持久化开关语义 | `db-enabled=false` 默认路径零变化；新增约束只限制非法组合 |
| C7' | trace limit 上限 500 | `findEventsByRunId().limit(500)` 逻辑不变——**注意**：step 事件增多会稀释 500 条窗口内其他事件占比，高频循环长 run 的 trace 截断风险已知，记账不处理（缓解：step 事件 payload 轻量） |

## 5. 分阶段提交计划

| Phase | Commit | 内容 |
|---|---|---|
| 1.1 | feat(runtime): RunEventType 4 新事件 + fromWireName 别名 | 契约层 |
| 1.2 | feat(runtime): observation payload 富化 API + RunCoordinator emit 升级 | 协调器 |
| 1.3 | feat(runtime): CompletionVerifier SPI + run.completed 发射闭环 | 桥 + 验证器 |
| 1.4 | feat(runtime): turn/step emit 埋点（ChatServiceImpl + 5 引擎 StepEventEmitter） | 引擎过渡态 |
| 1.5 | feat(config): 双开关一致性 fail-fast | 配置 |
| 1.6 | test(runtime): smoke 断言更新 + 全量回归绿 | 收尾 |
| 2.1-2.5 | 每引擎一 commit | 迁移 |
| 2.6 | refactor(kernel): API 定稿 + 死代码清理 | 收尾 |

## 6. 关键设计决策记录（ADR 摘要）

- **ADR-1 turn/step 用 observation 而非状态机迁移**：状态机（RunStatus/RunTransitionPolicy）是 run 级聚合根边界，turn/step 是 run 内部结构，不配拥有状态。observation 路径终态后可追加、零迁移风险。
- **ADR-2 CompletionVerifier 是 SPI 而非硬编码判据**：dsh "验证世界而非自我报告"的判据会演进（后续接真实 evaluator/eval gate），SPI 留出演进空间；MVP 判据 modelEnabled+answer 非空是最小可信集合。
- **ADR-3 verification.completed 改名走别名兼容**：直接改写存量行风险大（UPDATE 语句 + 断电中断），读侧别名映射是事件溯源系统改名的标准做法（写新读旧）。
- **ADR-4 Phase 1 引擎埋点是过渡态，接受 5 处重复**：与"消除重复"的目标相反但必要——先让事件地基独立可用可验证，Phase 2 内核就位后埋点沉入。若 Phase 1 就试图完美，两阶段耦合。
- **ADR-5 不动 AgentRuntimeEngine**：953 行旧一代实现，与 OparLoopEngine 路由互补互斥、无重叠段。动它是另一个项目。
- **ADR-6 StepEventEmitter 用 Supplier 包裹而非 AOP**：项目已有 ToolRuntimeAspect 的 AOP 教训（硬编码序列），显式包裹比注解魔法更可控、可测。

## 7. 验收标准

1. `mvn test` 全绿（基线 1091 + 新增）。
2. 端到端：一次成功 chat run 的 canonical 事件序列包含 `run.created → ... → turn.started → (step.started/step.completed)×N → turn.completed → verification.started → run.completed`，model.called payload 含 providerId/model，step.completed 含 stepIndex 与 durationMs。
3. 成功 run 终态为 `COMPLETED`（非 DEGRADED），fallback run 终态 DEGRADED reason=MODEL_UNVERIFIED_FALLBACK 语义正确。
4. 5 引擎行数合计下降 ≥40%（3165 → ~1900 含内核）。
5. 兼容性清单 §4 全部满足。
6. 双开关非法组合启动失败且错误信息可操作。
