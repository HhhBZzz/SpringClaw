# Agent Working Rules

> 任何 agent（人或机器）在动这个仓库之前，**先读这一份**。
>
> 这份规则不是为了限制工作，是为了防止「沉积层」继续累积——已经被三个 agent 在 48 小时内堆过一次了，下一次的代价会更高。

---

## 一、绝对禁止

### 1.1 禁止「为让测试通过而改测试断言」
出现失败断言时，**默认假设产品代码有问题，不是测试有问题**。

只有同时满足以下两个条件时，才允许改测试断言：
1. 产品代码的行为变化**是有意为之**（例如重构）
2. PR 描述中明确解释为什么旧断言不再适用，给出新旧对照

反例（必须避免）：
- 「测试期望 `结论：` 开头，但代码现在不输出这个前缀，所以删掉断言」← 错。先问代码为什么不输出了，是不是 regression
- 「mock 报 `verifyNoInteractions` 失败，改成 `verify(...)`」← 错。先问代码为什么调了不该调的东西

### 1.2 禁止「悄悄修改 Turn Contract 或 SOUL.md」
任何对以下文件的修改，**必须在独立 commit 中，commit message 必须以 `contract:` 或 `soul:` 开头**：
- `docs/TURN_CONTRACT.md`
- `docs/AGENT_WORKING_RULES.md`（本文件）
- `SOUL.md`
- `CLAUDE.md`

不允许在功能 commit 中顺手改这些文件。

### 1.3 禁止「未审就丢」
- `git stash drop` 之前必须先 `git stash show -p` 看一遍
- `git reset --hard` 之前必须先 `git stash push`
- 删除文件之前必须先 `git log -- <file>` 看历史

参考反面教材：本仓库 commit `bbba395` 删除了 `hasDirectCapabilityEvidence` 提前返回路径，引发"确定性结果被模型 summarize 改写"的架构退化，三个月后才被发现并恢复（commit `8f2d303`）。

### 1.4 禁止「单 commit 跨 3 个以上独立逻辑组」
错例：把 P0 修复 + 测试同步 + 文档更新 + 工具增强放在同一个 commit。

正例：每个 commit 描述能写成单句 "X: do Y"，且只动一类文件。

### 1.5 禁止「综述/审计文档」替代实现
R2 之后不再为每个子阶段同时写 `spec + plan + design`。

- 一个 PR 最多一份 plan；只有出现新的、不可逆的架构决策时，才允许单独写 design spec。
- 已有 spec、contract 或测试没有失效时，不再创建 `architecture consolidation`、`current-state-audit`、`paradigm v2` 这类重述文档。
- 不再维护跨 PR 的 collaboration ledger / status ledger；历史计划可以保留作证据，但不能继续追加当作当前状态源。
- R3.5 / B 目标默认直接进入实现；semantic 写入、迁移代码和回归测试优先于新增规划文档。

---

## 二、必须遵守

### 2.1 任何动 engine 链路的改动
（engine 链路 = `ChatServiceImpl` / `EngineSelector` / 6 个 engine 中任何一个 / `ToolOrchestrator` / `ToolRuntimeAspect` / `AgentDecisionService`）

必须满足以下**全部**：
- [ ] 本地跑过 `mvn test`，记录 commit 前后 tests run 数变化
- [ ] 本地启动服务，跑过 3 类典型 e2e（参考 §3）
- [ ] PR 描述写明「我修改了 engine 链路，验证了以下 3 类场景：...」
- [ ] 不引入新的 engine 类（除非有非常充分的理由）

### 2.2 任何新加 ToolPack 的 PR
必须包含：
- `@Tool` 注解的方法**至少 1 个**有 description ≥ 30 字符
- 对应的 `ToolPackDescriptor` 填写完整：`id`、`toolset`、`riskLevel`、`triggerKeywords`（≥3 个）
- 一个 `*ToolPackTest` 验证主路径
- 一个 `MessageEventToolAuditServiceTest` 用例验证 audit JSON 符合 `springclaw.tool-audit.v1` schema
- 若 `riskLevel ∈ {write, side_effect, dangerous}`，**必须**加入 `application.yml` 的 `user-deny-tools`，并在 `ApplicationYamlPolicyTest` 中守护

### 2.3 任何新加 `application.yml` 配置项
必须：
- 有 `${VAR_NAME:default}` 形式的环境变量覆盖
- 有 `ApplicationYamlPolicyTest` 中的断言（至少验证 key 存在 + 类型正确）
- 在本文档、README 或贴近配置的运维文档中提及；不要新增 `CORE_MODULE_PROGRESS` / `PROJECT_STATUS` 类快照文档

### 2.4 任何新加 trace 字段
必须：
- 在 `docs/TURN_CONTRACT.md` §2 中登记
- 后端写入 + 前端类型同步（`frontend/src/types.ts`）
- 一个集成测试验证字段真的会被写入

### 2.5 任何动 Workspace 边界的改动
（`WorkspaceEditToolPack` / `LocalFilesystemService` / `WorkspaceTaskService` / `LocalFilesCapabilityExecutor`）

必须：
- e2e 验证一次「USER 角色尝试越界 → 被拒」
- e2e 验证一次「ADMIN 角色合法操作 → 成功」
- 改动后 `TEST_*` 临时文件**全部清理**（不要留 `TEST_AGENT_WRITE_CHECK.md` 这种残留）

### 2.6 文档唯一真相源
长期入口只保留少数几类：

- `README.md` / `CLAUDE.md`：项目概览、运行方式和开发命令。
- `docs/AGENT_WORKING_RULES.md`：协作规则和文档预算。
- `docs/TURN_CONTRACT.md`：turn / trace 契约。
- `docs/ACCEPTANCE_CHECKLIST.md`：验收清单。
- `docs/superpowers/plans` / `docs/superpowers/specs`：只允许作为历史证据或单 PR 实施计划，不作为当前状态看板。

`docs/memory-bank` 不再承载项目状态或架构摘要。运行时学习数据默认写入 `data/memory-bank`，并由 git ignore 管理，避免把自动生成记忆伪装成项目文档。

---

## 三、典型 e2e 场景清单

接手时跑一次。改 engine 链路后跑一次。push 前再跑一次。

### 场景 A：read-only 不进自主循环
```bash
TOKEN=<your-token>
curl -s -X POST http://127.0.0.1:18080/api/chat/async \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"分析当前项目结构，不要修改任何文件","sessionKey":"e2e-rule-A"}'
```
**验证**：
- `logs/springclaw.log` 中**没有** "自主循环步骤" 日志
- `logs/springclaw-model.log` 中**没有** `source=autonomous-act-*`
- 工作区无文件变化

### 场景 B：USER 角色写文件被拒
```bash
curl -s -X POST http://127.0.0.1:18080/api/chat/async \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"在项目根目录创建一个 TEST_RULE_B.md 文件","sessionKey":"e2e-rule-B"}'
```
**验证**：
- 响应中包含「角色无权限」或类似措辞
- 项目根目录无 `TEST_RULE_B.md`
- `agent_run.status = FAILED`
- 至少一条 `message_event` 的 `content` 包含 `"status":"DENIED"`

### 场景 C：控制面确定性查询
```bash
curl -s -X POST http://127.0.0.1:18080/api/chat/async \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"你的内置模型是什么","sessionKey":"e2e-rule-C"}'
```
**验证**：
- 响应包含 `"我是 SpringClaw，当前使用"` 字样（friendlyProviderName 格式）
- `springclaw-model.log` 中**没有** `source=agent-runtime-summary`（说明没经过模型 summarize）

---

## 四、上下文交接规则

### 4.1 接手前必查
- [ ] `git status --short` —— 看工作区是否干净
- [ ] `git stash list` —— 看是否有未审 stash
- [ ] `git log --oneline -10` —— 看最近改了什么
- [ ] `mvn test 2>&1 | tail -5` —— 看测试基线
- [ ] `lsof -ti:18080` —— 看是否有遗留进程
- [ ] `ls TEST_*.md` —— 看是否有 e2e 残留

### 4.2 上一个 agent 留下了「未追踪文件」
**默认假设有价值**，先看内容再决定。**绝对不要直接 `git clean -fd`**。

### 4.3 上一个 agent 给的 commit 命令
- 不要直接执行——先 verify 自己理解了它在做什么
- 不要扩大范围——它给的 `git add` 文件列表是精确的，不要顺手加别的
- 不要缩小范围——除非有明确理由，否则按它列的全部 add

### 4.4 多个 agent 在短时间内动同一仓库
出现「沉积层」是必然的。接手时第一件事不是写代码，是：

1. 用 `git status` 分清「这一轮要做的」vs「上一轮没收尾的」
2. 把「上一轮没收尾的」**stash 或独立 commit**，不要混进本轮
3. 报告状态后再开始本轮真正的工作

参考本仓库 commit `8f2d303` 之前的接手流程作为正面范例。

---

## 五、变化日志

- **2026-06-12** — 初版。基于本仓库前三轮 agent 接手（Qoder / Codex / Claude）的教训写成。
