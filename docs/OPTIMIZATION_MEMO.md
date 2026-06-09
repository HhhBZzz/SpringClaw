# SpringClaw 优化备忘录

> 2026-06-09 架构审查 + 对标主流 Agent 产品后的优化决策
> 2026-06-09 更新：Phase 1-5 全部完成

---

## 一、审查结论（已达成共识）

### 1. 项目定位
SpringClaw 是一个基于 Spring Boot 3.5 + Spring AI 1.1 的企业 AI Agent 运行时。
能力覆盖面广（多模型、记忆、工具治理、飞书接入、Vue 后台），但架构演进中积累了结构性债务。

### 2. Agent 架构模式
当前采用 **Plan-then-Execute + ReAct 混合模式**（OPAR = Observe/Plan/Act/Reflect）。
这是主流模式，选择正确。问题不是"选什么模式"，而是"同样的模式实现了三遍"
（BasicStreamEngine / SimplifiedOparEngine / OparLoopEngine）。

### 3. 最严重的结构问题
- Pipeline 层 38 个文件实际等价于 `return message.trim()`
- ChatServiceImpl 1311 行 God Class（5 个构造函数、5 个分支）
- AgentRuntimeEngine 941 行承载 14 个子职责
- 两套平行领域模型（核心层 vs lifecycle 层）
- 54 处重复工具方法（containsAny x10, safe x22, normalize x13, truncate x9）
- 27/28 异常类从未被 throw

### 4. Skill 系统与主流标准的偏差
Agent Skills 已是开放标准（agentskills.io），35+ 产品采纳。
核心设计：Skill = 文件夹 + SKILL.md（说明书），模型读说明书后自己用工具执行。
SpringClaw 的 SKILL.md 格式已接近标准，但背后建了完整的扫描-注册-匹配-执行管线，
而标准做法是"渐进式披露 + 模型自己判断"。

### 5. 前端 API 契约（改造红线）
- 10 种 SSE 事件（token/status/meta/decision/trace/tool_call/skill_call/verification/action_required/error/done）
- REST 端点不变（/api/chat/stream, /api/chat/history, /api/runtime-console/* 等）
- responseMode 语义不变（agent/fast/deep）

---

## 二、优化执行记录

### Phase 1: 止血 ✅ 已完成

| 序号 | 任务 | 结果 |
|------|------|------|
| 1 | 删除 pipeline/ + lifecycle/ 死文件 | 删除 38 个文件 |
| 2 | 删除未使用的异常类 | 删除 28 个异常类 + 清理 GlobalExceptionHandler |
| 3 | 抽取 TextUtils 公共工具类 | 替换 54 处重复方法定义 |
| 4 | 修复 TextUtils 替换后的行为差异 | ContextAssembler 改用 normalizeWS()，DefaultWebhookSecurityService 保留 "unknown" 哨兵值 |
| 5 | 编译验证 | 236 主文件 + 74 测试文件编译通过 |

### Phase 2: 瘦身 ✅ 已完成

| 序号 | 任务 | 结果 |
|------|------|------|
| 6 | 抽取 SseEventBridge | 新建 @Component ~420 行，ChatServiceImpl 从 1307→730 行 |
| 7 | 精简 ChatServiceImpl 构造函数 | 4→2 个构造函数（18 参生产 + 11 参测试兼容） |
| 8 | 更新测试 | ChatServiceImplModeTest 适配新构造函数 |
| 9 | 编译验证 | 278 测试全部通过 |

### Phase 3: 技能系统对齐 ✅ 已完成

| 序号 | 任务 | 结果 |
|------|------|------|
| 10 | 统一三套打分算法 | SkillRegistryService.score() 改为 public，ScriptSkillCatalogService 委托给 Registry |
| 11 | 删除死代码 | MarkdownSkillCatalogService.matchDefinition() 零调用，已删除 |
| 12 | 验证 BuiltinSkillExecutionService | 已正确委托给 SkillRegistryService，无需修改 |
| 13 | 精简 LocalSkillFallbackService | 修复重复 import，删除无调用构造函数（6→5），694→657 行 |
| 14 | 编译验证 | 278 测试全部通过 |

### Phase 4: 安全加固 ✅ 已完成

| 序号 | 任务 | 结果 |
|------|------|------|
| 15 | docker-compose 凭据外部化 | 5 处明文密码→`${VAR:-default}` 模式，新建 .env.example |
| 16 | .dockerignore 补全 | 8 行→25 行，排除 .env*、frontend/、.git/ 等 |
| 17 | 创建 application-prod.yml | 强制 HTTPS cookie、限制 actuator、禁用 shell、WARN 日志 |
| 18 | docker-compose 默认 prod profile | 添加 SPRING_PROFILES_ACTIVE 环境变量 |
| 19 | 安全审计 | 发现 .run/ 和 .idea/ 中存有真实 API Key，已建议轮换 |
| 20 | 编译验证 | 278 测试全部通过 |

### Phase 5: 统一 AgentLoop ✅ 已完成

设计文档：`PHASE5_DESIGN.md`

| Step | 任务 | 结果 |
|------|------|------|
| 1 | 提取 LocalExecutionSupport | 新建 @Service，OparLoopEngine 和 SimplifiedOparEngine 共享本地兜底逻辑 |
| 2 | 修测试构造函数 + 删除遗留路径 | ModeTest/PersistenceTest 全部使用 EngineSelector，移除 runAgentExecution 遗留分支 |
| 3 | resolveFinalAnswer 委托给 LocalExecutionSupport | ChatServiceImpl 不再直接调用 oparLoopEngine 的本地兜底方法 |
| 4 | BasicStreamEngine 加 stream() | 新建 stream() 方法（含 SSE 生命周期），ChatServiceImpl 删除 streamBasicModelAnswer (~100 行) |
| 5 | 新建 ModelLedStreamEngine | 独立 @Service 承载模型主导流式 + ToolContext 生命周期，ChatServiceImpl 删除 streamModelLedAnswer (~120 行) |

**实际效果**：ChatServiceImpl 从 888→621 行（-30%），引擎策略覆盖率 100%。

---

## 三、改造红线
- 前端代码不动
- 10 种 SSE 事件名和载荷格式不变
- REST 端点路径和返回格式不变
- responseMode 语义不变
- 飞书 webhook 和长连接正常工作

---

## 四、累计变更统计

| 指标 | 数值 |
|------|------|
| 删除文件 | 38 个 Pipeline 文件 + 28 个异常类 + 1 个废弃方法 |
| 新增文件 | SseEventBridge.java, LocalExecutionSupport.java, ModelLedStreamEngine.java, application-prod.yml, .env.example, PHASE5_DESIGN.md |
| 净减代码 | ~1700 行（主代码 + 死文件） |
| ChatServiceImpl | 1307→621 行（-52%），5→2 个构造函数 |
| 构造函数精简 | ChatServiceImpl 5→2, LocalSkillFallbackService 6→5 |
| 安全评分 | docker-compose 明文密码 5→0，.dockerignore 8→25 行 |
| 测试状态 | 278 测试全部通过，0 失败 |
