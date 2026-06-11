# SpringClaw — 企业级 AI Agent 运行时

基于 **Spring Boot 3.5 + Spring AI 1.1** 构建的企业内 AI Agent 后端平台。支持多模型对话、短期/长期记忆、Agent 自主决策、工具与技能治理、飞书/API 多渠道接入，以及 Vue 3 管理后台。

---

## 核心能力

### 1. 多模型智能对话
- 已接入 **Claude**、**DeepSeek**、**Qwen** 等多种模型
- 支持 provider / model 运行时切换与故障自动转移
- 默认 **simplified 模式**处理常规问答，**OPAR 完整循环**处理复杂多步任务
- 复杂任务自动识别并升级链路，无需手动干预

### 2. 双轨记忆系统
- **短期记忆**：MySQL 会话事件流，重启不丢失
- **长期记忆**：Redis Vector Store + Embedding 语义召回
- 上下文组装自动注入相关历史记忆

### 3. Agent 决策与工具治理
- 规则路由 + 轻量模型分类，精准判断用户意图
- 写入、副作用、危险操作自动生成**确认提案（Proposal）**
- 全链路 **requestId 追踪**，每一步可审计
- AOP 统一拦截：权限校验、限流、审计日志

### 4. Skill 技能平台
- 目录化技能定义（`SKILL.md`），支持 Python / Builtin / Prompt 三种类型
- 内置常用技能：代码分析、日志诊断、系统状态、内容摘要、加密货币价格、RSS 订阅等
- 脚本技能可动态创建、热加载，无需重启服务

### 5. 多渠道接入
- **API**：RESTful 接口，支持同步/流式/异步三种聊天模式
- **飞书**：Webhook + 长连接双模接入，私聊/群聊上下文隔离
- 渠道适配器可扩展（含 Telegram、微信适配器接口）

### 6. 企业治理与安全
- 注册/登录/退出/Token 吊销，HttpOnly Cookie 登录态
- 基于角色的权限控制（`@RequireRole` 注解）
- 管理后台仅限 ADMIN 角色访问
- 模型调用用量统计（总次数、Token 消耗、Top Provider/Model）

### 7. Vue 3 管理后台
- 用户与角色管理
- 模型状态与切换
- 工具权限与技能策略
- 缓存、记忆、审计日志
- 活跃 Token 会话监控
- 模型用量可视化

### 8. 高可用策略
- **AI 优先，本地兜底**：模型可用时走 AI，不可用时自动降级到本地技能
- 天气、汇率、新闻、系统信息等高频查询不依赖大模型
- 优雅降级，绝不因单点故障影响整体可用性

---

## 项目结构

```
springclaw/
├── src/main/java/com/springclaw/
│   ├── controller/          # HTTP 接口层（聊天、认证、管理、Webhook）
│   ├── service/
│   │   ├── chat/            # 聊天主链路（SimplifiedEngine / OparLoopEngine）
│   │   ├── agent/           # Agent 决策、路由、追踪
│   │   ├── memory/          # 长期向量记忆
│   │   ├── context/         # 上下文组装
│   │   ├── skill/           # Skill 目录、运行时、脚手架
│   │   └── ai/              # 模型 Provider 管理与故障转移
│   ├── tool/
│   │   ├── pack/            # Spring AI @Tool 工具包
│   │   └── runtime/         # 工具 AOP 治理（权限、限流、审计）
│   ├── strategy/            # 渠道适配器策略模式
│   ├── web/auth/            # 认证与授权拦截器
│   └── config/              # 配置类
├── frontend/                # Vue 3 + Vite 前端
├── skills/                  # Skill 技能目录（Python/Builtin）
├── docker-compose.yml       # Docker 一键部署
├── Dockerfile
├── pom.xml
└── SOUL.md                  # Agent 人格定义
```

---

## 快速启动

### 前置要求
- **JDK 17+**
- **Maven 3.8+**
- （可选）Docker Desktop

### 方式一：本地最简启动（无需数据库）

```bash
# 不配真实模型 Key 也能启动，走本地技能兜底
OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run
```

启动后测试：

```bash
# 健康检查
curl http://127.0.0.1:18080/actuator/health

# 发送聊天
curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"你好，介绍一下你自己","channel":"api"}'
```

### 方式二：Docker 一键启动（含 MySQL + Redis + RabbitMQ）

```bash
OPENCLAW_PRIMARY_API_KEY=test-key docker compose up -d --build
```

这会把应用连同 MySQL 8.0、Redis Stack、RabbitMQ 一起启动，端口 `18080`。

### 方式三：配置真实大模型

```bash
export OPENCLAW_PRIMARY_API_KEY=你的真实Key
export OPENCLAW_PRIMARY_BASE_URL=https://api.openai.com
export OPENCLAW_PRIMARY_MODEL=gpt-4o
mvn spring-boot:run
```

支持多 Provider 配置，详见 `application.yml` 中以 `${OPENCLAW_*}` 开头的环境变量。

---

## 前端开发

```bash
cd frontend
npm install
npm run dev        # 开发模式 → http://localhost:5173/#/agent
npm run build      # 生产构建 → frontend/dist/
```

Vite 自动将 `/api/*` 代理到后端 `localhost:18080`。

---

## 主要 API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/chat/send` | POST | 同步聊天 |
| `/api/chat/stream` | POST | SSE 流式聊天 |
| `/api/chat/async` | POST | 异步聊天（RabbitMQ） |
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/me` | GET | 当前用户信息 |
| `/api/webhook/feishu` | POST | 飞书 Webhook 入口 |
| `/admin` | GET | 管理后台页面 |

---

## 环境变量速查

| 变量 | 说明 | 示例 |
|------|------|------|
| `OPENCLAW_PRIMARY_API_KEY` | 主模型 API Key | `sk-xxx` |
| `OPENCLAW_CODING_PLAN_API_KEY` | Coding Plan 模型 Key | `sk-xxx` |
| `OPENCLAW_DEEPSEEK_API_KEY` | DeepSeek 模型 Key | `sk-xxx` |
| `OPENCLAW_EMBEDDING_API_KEY` | Embedding 模型 Key | `sk-xxx` |
| `OPENCLAW_CHAT_AGENT_MODE` | 聊天模式 | `simplified` / `opar` |
| `OPENCLAW_FEISHU_OUTBOUND_ENABLED` | 飞书主动回消息 | `true` / `false` |
| `OPENCLAW_FEISHU_LONG_CONNECTION_ENABLED` | 飞书长连接模式 | `true` / `false` |
| `MYSQL_ROOT_PASSWORD` | MySQL Root 密码 | `root` |
| `SPRING_PROFILES_ACTIVE` | Spring Profile | `dev` / `prod` |

完整配置参见 `application.yml` 和 `RUN_REAL_ENVIRONMENT.md`。

---

## 技术栈

| 层次 | 技术选型 |
|------|----------|
| 框架 | Spring Boot 3.5 |
| AI | Spring AI 1.1.2 |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0 |
| 缓存/向量 | Redis Stack（RediSearch） |
| 消息队列 | RabbitMQ |
| 分布式锁 | Redisson 3.32 |
| 定时任务 | XXL-JOB 2.4.1 |
| 飞书 SDK | Lark OAPI 2.5.3 |
| 前端 | Vue 3 + Vite + Pinia + Vue Router |
| 构建 | Maven 3.8+, JDK 17 |

---

## 项目亮点

1. **多模型 Agent 运行时**：模型不硬编码，支持 provider/model 切换与故障转移。
2. **平台化 Agent 能力**：决策、工具风险分级、动作确认、全链路追踪作为平台层能力，而非散落在业务代码中。
3. **统一工具治理**：基于 Spring AI `@Tool` + AOP，权限、限流、审计集中收口。
4. **Skill 生态**：类 Hermes 风格技能体系，Python/Builtin/Prompt 统一管理、热加载、使用统计。
5. **AI 优先 + 本地兜底**：正常走大模型，异常时优雅降级，核心功能不受单点故障影响。

---

## 相关文档

- [CHANGELOG.md](./CHANGELOG.md) — 变更日志
- [RUN_REAL_ENVIRONMENT.md](./RUN_REAL_ENVIRONMENT.md) — 真实环境部署指南
- [SOUL.md](./SOUL.md) — Agent 人格定义
- [CLAUDE.md](./CLAUDE.md) — Claude Code 开发指引
- [docs/](./docs/) — 面试资料、项目状态、验收清单等

---

## GitHub

- 仓库：[HhhBZzz/SpringClaw](https://github.com/HhhBZzz/SpringClaw)
- 当前分支：[codex/bootstrap-github](https://github.com/HhhBZzz/SpringClaw/tree/codex/bootstrap-github)
