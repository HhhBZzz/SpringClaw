# OpenClaw Java

一个基于 `Spring Boot 3.5 + Spring AI 1.1` 的企业内 AI Agent 后端。

截至 `2026-03-20`，项目已经完成从“可跑 Demo”到“可持续演进的内部 Agent 服务”的第一轮收敛，具备多模型、短期/长期记忆、权限治理、飞书接入和管理后台能力。

## 当前能力概览

1. 多模型聊天
   - 已接入 `coding-plan`、`deepseek`
   - 支持 provider/model 切换
   - 默认主链路已切到 `simplified`
2. 记忆系统
   - 短期：MySQL 会话事件流
   - 长期：Redis Vector Store + `text-embedding-v4`
3. 权限与治理
   - 注册/登录/token
   - `ADMIN` 后台
   - 工具权限与技能策略
4. 渠道接入
   - API
   - 飞书 webhook
   - 飞书长连接
5. 管理后台
   - `/admin`
   - 用户、角色、模型、缓存、记忆、审计、活跃 token 会话

## 截止今天的项目状态

当前主线已经落地：

1. 默认模式是 `simplified`
   - 简单问题更快
   - 工具调用更自然
   - `opar` 仍保留为可选高级模式
2. 长期记忆已经打通真实 Redis 向量存储
   - 不再只是本地回退内存
3. 后台改成 `ADMIN only`
   - 非管理员不能查看管理数据
4. 飞书上下文边界已经收敛
   - 私聊和群聊分作用域
   - 群聊保留群共享记忆
   - 群聊不再补召回用户私聊/跨场景个人长期记忆
5. 项目已接入 GitHub 记录
   - 仓库：[HhhBZzz/SpringClaw](https://github.com/HhhBZzz/SpringClaw)
   - 当前工作分支：[codex/bootstrap-github](https://github.com/HhhBZzz/SpringClaw/tree/codex/bootstrap-github)

## 先看代码分工

- 接收消息（Controller）：`src/main/java/com/openclaw/controller`
- 聊天主链路（Service）：`src/main/java/com/openclaw/service/chat/impl/ChatServiceImpl.java`
- 记忆与上下文：`src/main/java/com/openclaw/service/context`、`src/main/java/com/openclaw/service/memory`
- 给大模型用的工具（Tool）：`src/main/java/com/openclaw/tool/pack`
- 后台与运维接口：`src/main/java/com/openclaw/controller/ops`

## 怎么启动

### 1) 最快启动（本地演示，不依赖真实大模型）

```bash
OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run
```

启动后测试：

```bash
curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"你好，帮我介绍下自己","channel":"api"}'
```

### 2) Docker 一键启动（MySQL + Redis + 应用）

```bash
# 不配真实大模型 key 也能启动（会走本地技能/降级链路）
OPENCLAW_PRIMARY_API_KEY=test-key docker compose up -d --build
```

验证：

```bash
curl http://127.0.0.1:18080/actuator/health
curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"docker-1","userId":"u1","message":"你好，帮我查北京天气","channel":"api"}'
```

### 3) 真实环境启动（手动配置）

看这个文档：`RUN_REAL_ENVIRONMENT.md`

### 4) API Key 说明

- 要测试真实大模型回复，至少要配置一个可用 provider 的真实 key
- 当前主线优先使用：
  - `OPENCLAW_CODING_PLAN_API_KEY`
  - `OPENCLAW_DEEPSEEK_API_KEY`
- embedding 独立配置：
  - `OPENCLAW_EMBEDDING_API_KEY`
  - `OPENCLAW_EMBEDDING_MODEL=text-embedding-v4`
- 如果只给 `test-key`，系统仍可启动，但会更多依赖本地技能/降级链路

## 要不要连 MySQL / Redis

- 不连也能跑：聊天接口、飞书接入、本地技能兜底都可用
- 连 MySQL 后增强：
  - 会话/事件审计持久化（重启不丢）
  - 用户/角色/技能策略管理可落库
- 连 Redis 后增强：
  - 向量记忆（Redis Vector Store）
  - token、限流、会话锁可分布式化（依配置开关）

一句话：不连是“可演示版”，连上是“可持续运行版”。

## 这个项目现在最核心的 4 个功能

1. 聊天：`POST /api/chat/send`、`POST /api/chat/stream`、`POST /api/chat/async`
2. Skill + 工具调用：通过 `skill_descriptor/skill_policy` 控制可用技能，再动态注入 `System`、`File`、`WorkspaceSearch`、`WebSearch`、`Weather`、`Exchange`、`News`、`ScriptSkill` 工具包
3. 记忆：MySQL 事件流 + Redis 向量记忆 + Spring AI Advisor 上下文注入
4. 企业治理：认证、角色、工具权限、审计、后台管理

## 3 条面试可背的项目亮点

1. 我做了多模型 Agent 运行时，不把模型写死在代码里，支持 provider/model 切换与故障切换。
2. 我用 Spring AI 的 `@Tool` + AOP 做工具统一治理，把权限、限流、审计收在同一层。
3. 我做了双轨记忆（MySQL 事件流 + Redis 向量检索），既能审计，也能做长期语义召回。

## Skill 快速使用（可选）

默认情况下（不开数据库）会使用内置技能：

- `system-basic`（系统时间/UUID/JVM）
- `file-basic`（受控目录文件操作）
- `workspace-search`（不知道路径时可按文件名/关键词检索项目，也可自动分析“某功能在哪实现”）
- `web-basic`（联网搜索公开网页、抓取 URL 文本）
- `weather-basic`（查询天气）
- `exchange-basic`（查询汇率）
- `news-basic`（查询新闻）
- `script-basic`（执行受控 Python 技能，默认关闭）

脚本技能说明：
- 技能目录：`skills/`
- 新增/删除模板与操作说明：`docs/SCRIPT_SKILL_GUIDE.md`
- 当前内置 3 个脚本技能：
  - `repo_inspector`：分析当前项目里某个功能/类/配置在哪实现
  - `runtime_probe`：排查端口占用、JVM 进程、启动失败
- 开启方式：`openclaw.tools.script.enabled=true`
- 强约束：仅允许执行白名单中的技能文件（`allowed-skills`）

## 认证与后台

### 认证接口

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/me`（支持 `Authorization: Bearer <token>`）

说明：
- 聊天接口现在优先信任登录态，不再建议依赖手填 `userId` 冒充身份
- 飞书侧如果需要做角色区分，当前约定是：把飞书 `userId/openId` 作为内部用户名使用

### 审计接口

- `GET /api/admin/audit/logs`
- `GET /api/admin/audit/stats`

### 后台管理页

- 访问：`/admin`
- 当前能力：
  - 系统快照
  - 用户与角色
  - 模型状态与切换
  - 工具权限
  - Skill 管理
  - 缓存 / 记忆 / RabbitMQ
  - 审计日志
  - 活跃 token 会话
- 访问控制：
  - 仅 `ADMIN` 可查看和操作后台数据

## 可用性策略（AI 优先，本地兜底）

- 当前不是“凡是能本地做就先本地做”，而是：
  - 控制面和高确定性问题优先本地：
    - 当前模型 / 切换模型
    - JVM / UUID / 明确系统命令
    - 最近失败原因、上下文规模
  - 普通问题优先交给 AI：
    - 自然问答
    - 工具选择
    - 结果表达
  - 只有模型不可用、超时、熔断时，才回退到本地工具兜底
- 本地兜底仍覆盖：
  - 日期时间
  - 天气、汇率、新闻
  - 项目检索、联网检索
  - 脚本技能（可选）

一句话：本地层负责确定性执行，AI 负责理解和表达。

## 飞书接入（当前实现）

当前已支持渠道 `feishu`，统一入口：

- `POST /api/webhook/feishu`
- 可选开启“自动回消息到飞书会话”（需配置 `OPENCLAW_FEISHU_OUTBOUND_ENABLED=true` + 飞书 `app-id/app-secret`）
- 可选切换“长连接模式”（不依赖公网 Webhook）：
  - `OPENCLAW_FEISHU_LONG_CONNECTION_ENABLED=true`
  - `OPENCLAW_FEISHU_APP_ID=...`
  - `OPENCLAW_FEISHU_APP_SECRET=...`

本地快速模拟飞书消息：

```bash
curl -X POST http://127.0.0.1:18080/api/webhook/feishu \
  -H 'Content-Type: application/json' \
  -d '{
    "event": {
      "sender": {"sender_id": {"open_id": "ou_xxx"}},
      "message": {
        "chat_id": "oc_xxx",
        "chat_type": "p2p",
        "message_type": "text",
        "content": "{\"text\":\"你好，来自飞书\"}"
      }
    }
  }'
```

### 飞书上下文策略（截至 2026-03-20）

1. 私聊
   - 按私聊会话记忆
   - 允许正常的同用户长期语义记忆补充
2. 群聊
   - 保留群共享会话上下文，方便做“今天群里聊了什么”的总结
   - 不再把用户私聊或其他场景的个人长期记忆补召回到群里
   - 群上下文中会尽量标出说话人，例如 `USER(ou_xxx)`

一句话：群助手仍然像“在群里听大家说话”，但不会把个人私聊记忆污染进来。

## GitHub 记录

当前项目已经同步到 GitHub，适合每天看变更记录：

- 仓库：[HhhBZzz/SpringClaw](https://github.com/HhhBZzz/SpringClaw)
- 当前工作分支：[codex/bootstrap-github](https://github.com/HhhBZzz/SpringClaw/tree/codex/bootstrap-github)
- 提交记录：[Commits](https://github.com/HhhBZzz/SpringClaw/commits/codex/bootstrap-github)
- 变更日志：[CHANGELOG.md](/Users/hanbingzheng/springclaw/CHANGELOG.md)

## 面试资料

1. [INTERVIEW_PROJECT_BRIEF.md](/Users/hanbingzheng/springclaw/docs/INTERVIEW_PROJECT_BRIEF.md)
2. [PROJECT_STATUS.md](/Users/hanbingzheng/springclaw/docs/PROJECT_STATUS.md)
3. [ACCEPTANCE_CHECKLIST.md](/Users/hanbingzheng/springclaw/docs/ACCEPTANCE_CHECKLIST.md)
4. [RUN_REAL_ENVIRONMENT.md](/Users/hanbingzheng/springclaw/RUN_REAL_ENVIRONMENT.md)
