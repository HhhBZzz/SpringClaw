# Turn Contract

> SpringClaw 现有 6 个 engine（`basic-stream` / `model-led-stream` / `simplified` / `agent-runtime` / `autonomous-loop` / `opar-loop`）。本文档不要求它们合并，但要求它们的**外部可观测行为**遵守同一份契约。
>
> 任何对 engine 链路的改动，**先读这份契约**。任何新加 engine，**必须满足这份契约**。

---

## 一、什么是 "一次 turn"

**Turn** = 用户发出一条消息到该消息得到最终回答的完整过程。一个 turn 用 `requestId` 唯一标识。

一个 chat 请求 ↔ 一个 turn ↔ 一个 `requestId` ↔ 一行 `agent_run` 记录。

不在 turn 范围内的：
- 后台 health check、metric scrape
- 模型决策路由（`agent-decision-router`）调用本身不算 turn，但它产生的决策属于其所在 turn
- async chat 的 dispatch 阶段不算独立 turn

---

## 二、契约（每次成功的 turn 必须满足）

### 2.1 持久化

每个 `requestId` 必须产生且仅产生：

- **1 条** `agent_run` 记录
  - 必填：`request_id`、`channel`、`user_id`、`started_at`、`status`
  - 终态必填：`finished_at`、`duration_ms`、`status ∈ {SUCCESS, FAILED, TIMEOUT}`
- **≥1 条** `agent_run_step` 记录，关联同一 `request_id`
  - 每条事件必填：`sequence_no`、`step_name`、`step_type`、`status`、`detail_json`
  - `sequence_no` 在同一 `request_id` 内**单调递增**，从 1 开始
- 若调用了模型：**≥1 条** `llm_usage_record` 记录关联同一 `request_id`
  - 必填：`provider`、`model`、`source`、`prompt_tokens`、`completion_tokens`、`elapsed_ms`
- 若调用了工具：**每个工具调用产生 2 条** `message_event` 记录（`event_type=TOOL`）
  - 第一条 `status=START`，第二条 `status ∈ {SUCCESS, FAILED, DENIED}`
  - 两条 `content` 都是 `springclaw.tool-audit.v1` JSON
  - 两条都关联同一 `request_id`

### 2.2 事件序列要求

- 每个 turn 至少包含三类 step：
  - **`route`** 类型（恰好 1 条）—— 决策路由结果
  - **`agent`** 类型（≥1 条）—— engine 执行过程
  - **`final`** 类型（恰好 1 条）—— 最终回答事件
- `final` step 必须是最后一条（`sequence_no` 最大）
- 所有 step 的时间戳必须单调递增（允许相等，禁止倒退）

### 2.3 `final` step 的 `detail_json` 必须包含

```json
{
  "requestId": "<匹配 agent_run.request_id>",
  "stepName": "<engine name 或人类可读>",
  "type": "final",
  "status": "success | failed",
  "detail": "<answer 摘要，不超过 500 字符>",
  "durationMs": <number>,
  "timestamp": <epoch ms>
}
```

附加可选字段（**强烈推荐**）：
- `modelEnabled` (bool) — 最终回答是否经过 model summarize
- `verificationLevel` (string) — "strong" / "acceptable" / "insufficient"
- `answerLength` (int) — 最终回答字符数

### 2.4 工具调用的 audit JSON 必须包含

```json
{
  "schema": "springclaw.tool-audit.v1",
  "eventType": "tool.invoke",
  "toolName": "<ClassName.methodName 或 @Tool name>",
  "toolset": "<对应 ToolPackDescriptor.toolset，不是类名>",
  "status": "START | SUCCESS | FAILED | DENIED",
  "normalizedStatus": "started | success | failed",
  "phase": "ACT | OBSERVE | PLAN | REFLECT",
  "detail": "<入参摘要或结果摘要>",
  "summary": "<人类可读单行>",
  "sessionKey": "<非空，等于 agent_run.session_key>",
  "channel": "<非空>",
  "userId": "<非空>",
  "requestId": "<非空，等于 agent_run.request_id>"
}
```

**修复历史**：
1. ~~控制面短路场景下 `requestId / sessionKey / userId` 为占位值~~ — **已修复**（commit `55e67e0`，2026-06-12）。`SimplifiedOparEngine` / `OparLoopEngine` 现在在调用本地短路前 `ToolExecutionContextHolder.open(LOCAL-SHORTCUT)`。守护测试：`TurnContractTest.toolAuditPropagatesContextFromHolder`。
2. ~~`toolset` 字段可能写入类名而非 descriptor.toolset~~ — **已修复**（commit `d40ae17`，2026-06-12）。`MessageEventToolAuditService` 现在通过 `CapabilityRegistry.findToolsetByClassName` 反查正确的 toolset。守护测试：`TurnContractTest.toolAuditToolsetMatchesDescriptor`。

新发现的违约场景必须先在 `TurnContractTest` 或 `MessageEventToolAuditServiceTest` 中标注 `@Disabled("known-gap")` 钉住，然后再排期修复——不要让违约悄悄通过。

### 2.5 权限事件

若某次工具调用被权限拒绝：
- `message_event` 的 `status` 必须是 `DENIED`
- `detail` 必须包含被拒原因（角色、被拒工具名）
- 该工具的 `agent_run_step` 必须是 `failed` 而不是 `success`
- **不允许**模型在 `detail` 之外编造"已完成"

### 2.6 失败的 turn

`agent_run.status = FAILED` 时仍需满足：
- `finished_at` 必填
- `final` step 仍需出现，但 `status = failed`，`detail` 含失败原因
- 若失败发生在调模型前，无需 `llm_usage_record`
- 若失败发生在工具调用中，对应工具的 audit JSON 必须有配对的 `FAILED` 或 `DENIED`

---

## 三、行为契约（每个 engine 必须）

| 行为 | 要求 |
|------|------|
| 模型不可用降级 | 走 `deterministicAnswer`，`final.detail_json.modelEnabled = false` |
| 高置信单一能力结果 | 走 `deterministicAnswer`，不调 `summarize`（参考 `AgentRuntimeEngine.hasDirectCapabilityEvidence`） |
| 控制面查询（`你的内置模型是什么` 等）| 一定走 deterministic 路径，不走 model 自由生成 |
| 工具被 deny | 模型不能在 deny 之后用纯文本声称完成；必须输出 `TASK_FAILED` 或同等失败标记 |
| `riskLevel=read` | 不暴露 `WorkspaceEditToolPack.*`，不进入 autonomous loop |
| `riskLevel=write/side_effect/dangerous` | 完成判断必须经过 `AutonomousExecutionTracker.satisfiesCompletionCondition` |
| `TASK_COMPLETE` 检测 | 必须经 `AutonomousLoopEngine.isMarkerPresent` 归一化，不允许各 engine 各自实现 |
| SSE start meta | `SseEventBridge.sendStartMeta` 必须调，且 forward 到 `AgentRunTraceService.recordRunMetadata` |

---

## 四、测试守护

以下测试是契约的**机器化守护**，删除或弱化任何一条都需要在 PR 描述中明确解释为什么。

| 测试 | 守护的契约项 |
|------|------------|
| `ApplicationYamlPolicyTest` | 2.5 默认权限策略 |
| `AgentRuntimeEngineTest.shouldAnswerStructuredRealtimeResultWithoutSummaryModel` | 3 行为契约「高置信单一能力结果不调 summarize」 |
| `AgentRuntimeEngineTest.shouldRenderCacheFriendlyStablePrefix...` | summarize prompt 模板稳定性 |
| `AutonomousExecutionTrackerTest$TaskCompleteDetectionTests` | 3 行为契约「TASK_COMPLETE 归一化」 |
| `AutonomousExecutionTrackerTest$WriteCompletionTests` 等 | 3 行为契约「假完成防护」 |
| `LocalSkillFallbackServiceTest.shouldAnswerCurrentModelForMuQianPhrase...` | 3 行为契约「控制面确定性」 |
| `MessageEventToolAuditServiceTest` | 2.4 audit JSON schema |
| `TurnContractTest` | §2.1 / §2.2 / §2.3 / §2.4 / §2.5 / §3 一站式守护，含 replayRun |

---

## 五、修改契约的流程

这份契约**不是不可变的**。但每次修改要：

1. 在 PR 描述中说明 **为什么** 要改契约（不要"为了让测试通过"）
2. 同步更新所有 engine 的实现，确保新契约对全部 6 个 engine 成立
3. 同步更新本文件，把变化日志写在文末

---

## 六、变化日志

- **2026-06-12** — 初版。基于 commit `e2967ef` 时的代码状态。
- **2026-06-12** — 关闭 §2.4 两个 known-gap：
  - `toolset` 类名 bug 由 commit `d40ae17` 修复（CapabilityRegistry 反查）
  - 本地短路 context 丢失由 commit `55e67e0` 修复（LOCAL-SHORTCUT scope）
  - 新增 `replayRun` 端点（commit `620b49f`）让契约 §2.1-§2.2 可从外部验证
  - 新增 `TurnContractTest` 11 个测试守护契约（commit `f742282`）
