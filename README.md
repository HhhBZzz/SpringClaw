# OpenClaw Java

一个基于 `Spring Boot 3 + Spring AI` 的企业级 Agent 项目。

## 先看代码分工

- 接收消息（Controller）：`src/main/java/com/openclaw/controller`
- 调大模型（Service）：`src/main/java/com/openclaw/service/chat/impl/ChatServiceImpl.java`
- 给大模型用的工具（Tool）：`src/main/java/com/openclaw/tool/pack`

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

- 你要测试“完整版大模型回复”，必须配置真实 `OPENCLAW_PRIMARY_API_KEY`
- 如果用 `test-key`，系统会走降级模式（接口可用，但不调用真实模型）

## 要不要连 MySQL / Redis

- 不连也能跑：聊天接口、飞书接入、本地技能兜底都可用。
- 连 MySQL 后增强：
  - 会话/事件审计持久化（重启不丢）
  - 用户/角色/技能策略管理可落库
- 连 Redis 后增强：
  - 向量记忆（Redis Vector Store）
  - Token、限流、会话锁可分布式化（依配置开关）

一句话：不连是“可演示版”，连上是“可持续运行版”。

## 这个项目最核心的 3 个功能

1. 聊天：`POST /api/chat/send` 和 `POST /api/chat/stream`
2. Skill + 工具调用：通过 `skill_descriptor/skill_policy` 控制可用技能，再动态注入 `System`、`File`、`WorkspaceSearch`、`WebSearch`、`Weather`、`Exchange`、`News`、`ScriptSkill` 工具包
3. 企业治理：简化认证（注册/登录/token）、工具权限校验（角色到工具）、审计日志分页与统计接口

## 3 条面试可背的项目亮点

1. 我实现了 OPAR Agent 闭环（Observe/Plan/Act/Reflect），不是普通问答接口。
2. 我用 Spring AI 的 `@Tool` + AOP 做了工具统一治理（限流、审计、降级）。
3. 我做了双轨记忆（MySQL 事件流 + Redis 向量检索），既可审计又能语义召回。

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

## 认证与审计（新增）

### 认证接口

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/me`（支持 `Authorization: Bearer <token>`）

说明：
- 聊天请求里的 `userId` 建议传注册用户名，这样工具权限会按该用户角色生效。

### 审计接口

- `GET /api/admin/audit/logs`（分页查询）
- `GET /api/admin/audit/stats`（统计聚合）

### 后台管理页（轻量版）

- 访问：`/admin/index.html`
- 功能：系统总览、用户管理、工具权限、Skill 管理、审计查询
- 说明：当 `openclaw.persistence.db-enabled=false` 时，管理写接口会提示不可用（只读能力仍可用）

## 可用性增强（AI 优先，本地兜底）

- 当前策略不是“凡是能本地做就先本地做”，而是：
  - 控制面与真实状态面优先本地：
    - 当前模型/切换模型
    - JVM/UUID/明确系统命令
    - 最近失败原因、上下文记忆规模
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
- 开关：
  - `openclaw.chat.local-fallback-enabled=true`
  - `openclaw.chat.local-fallback-first=true`

一句话：本地层负责确定性执行，AI 负责理解和表达。

如果你开启数据库持久化（`openclaw.persistence.db-enabled=true`），可用以下 SQL 控制技能：

```sql
INSERT INTO skill_descriptor
(id, skill_id, name, description, tool_pack, enabled, priority, create_time, update_time, deleted)
VALUES
(1001, 'system-basic', '系统基础技能', '时间、UUID、JVM 信息', 'system', 1, 10, NOW(), NOW(), 0),
(1002, 'file-basic', '文件技能', '受控目录读写', 'file', 1, 20, NOW(), NOW(), 0),
(1003, 'workspace-search', '项目检索技能', '不知道路径时可按关键词定位文件/代码', 'workspace', 1, 25, NOW(), NOW(), 0),
(1004, 'web-basic', '联网检索技能', '联网搜索公开网页并提取文本', 'web', 1, 30, NOW(), NOW(), 0);

-- 例子：禁止某用户在 telegram 使用 file-basic
INSERT INTO skill_policy
(id, channel, user_id, skill_id, allow, create_time, update_time, deleted)
VALUES
(2001, 'telegram', 'u1', 'file-basic', 0, NOW(), NOW(), 0);
```

## 飞书接入（最小可测）

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
        "message_type": "text",
        "content": "{\"text\":\"你好，来自飞书\"}"
      }
    }
  }'
```

## 面试资料

1. [INTERVIEW_PROJECT_BRIEF.md](/Users/hanbingzheng/springclaw/docs/INTERVIEW_PROJECT_BRIEF.md)
2. [PROJECT_STATUS.md](/Users/hanbingzheng/springclaw/docs/PROJECT_STATUS.md)
