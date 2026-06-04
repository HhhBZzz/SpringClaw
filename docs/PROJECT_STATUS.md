# PROJECT_STATUS

## 截至 2026-06-01 的结论

SpringClaw 已经从“可运行 Agent Demo”收敛为一个更完整的 Java 企业级 Agent Runtime。当前项目重点不再只是聊天接口，而是围绕 Agent 决策、工具治理、Skill 运行时、记忆审计、前端控制台和多渠道接入形成一套可持续演进的基座。

当前具备：

1. 多模型运行时与故障切换
2. 简化链路与 OPAR 链路并存
3. Agent 决策路由、动作确认与运行 trace
4. MySQL 事件流与 Redis 向量记忆
5. 认证、角色、后台与工具权限治理
6. 飞书 webhook / 长连接 / 回消息
7. runtime skill registry 与 Python / builtin / prompt skill 分发
8. Vue 3 管理与 Agent 控制台
9. GSAP 动效增强的前端交互
10. 模型用量、审计日志、活跃会话与运行态观测

## 当前已完成范围

### 1. 聊天与 Agent

- `POST /api/chat/send`
- `POST /api/chat/stream`
- `POST /api/chat/async`
- 默认模式：`simplified`
- 可选模式：`opar`
- 新增 Agent 决策层：
  - `AgentDecisionRouter`
  - `AgentDecisionService`
  - `AgentActionProposalService`
  - `ToolRiskPolicyService`
  - `AgentRunTraceService`
- 支持对写入、副作用、危险操作做风险分级和确认提示。

### 2. 模型能力

已接入：

- `coding-plan`
- `deepseek`

已支持：

- provider/model 查询
- provider/model 切换
- 模型失败后的同 provider 切换与降级
- 模型冷却与传输异常保护
- DeepSeek V4 Pro 兼容策略，避免不兼容的 Spring AI 原生 tool-calling 触发 400 类错误

### 3. 记忆与审计

短期：

- MySQL `message_event`
- MySQL `agent_session`

长期：

- Redis Vector Store
- embedding：`text-embedding-v4`

当前状态：

- 主实例已验证可以写入 Redis 向量索引并召回。
- 消息历史按登录用户过滤，避免跨用户读取历史。
- Agent trace 持久化到事件流，可按 requestId 查看运行步骤。

### 4. 权限与后台

已完成：

- 注册 / 登录 / 退出 / token revoke
- HttpOnly Cookie 登录态
- `ADMIN only` 后台
- 工具权限控制
- 技能策略控制
- 用户、角色、模型、缓存、记忆、审计、活跃 token 会话、模型用量管理

后台入口：

- `/admin` 会跳转到 Vue 前端后台
- Vue 路由 `/admin` 有登录与管理员守卫

### 5. 前端控制台

当前前端：

- Vue 3 + Vite + Pinia + Vue Router
- 旧 Spring Boot 静态 `agent/admin` 页面已被 Vue 控制台替代
- Agent 页面支持流式回复、运行状态、动作确认、trace 展示
- 后台页面展示 dashboard、token、审计、运行时 skill、模型用量等数据
- 新增 `gsap` 依赖与 `useAgentGsapMotion`，用于 Agent 控制台动效
- 动效尊重 `prefers-reduced-motion`

### 6. 飞书接入

已完成：

- webhook 接入
- 长连接接入
- 飞书回消息
- 飞书私聊/群聊作用域区分

当前策略：

- 私聊保留个人连续记忆
- 群聊保留群共享上下文
- 群聊不再补召回用户私聊或跨场景个人长期记忆

### 7. 工具与 Skill

当前主要工具包：

- `system`
- `file`
- `local-files`
- `workspace-search`
- `workspace-review`
- `web`
- `weather`
- `exchange`
- `news`
- `script`
- `skill-library`

Skill 当前收口为：

- `BUILTIN`
- `PYTHON`
- `PROMPT`
- 兼容历史 `SCRIPT`

已支持：

- 后台查看 runtime skill registry
- 从远程 `SKILL.md` / ClawHub 页面导入 Markdown skill
- Python skill 通过 `SkillRuntimeService` 统一执行
- builtin skill 通过专门 handler 执行
- prompt skill 保留为说明型能力，不直接当作任务执行
- `ToolOrchestrator` 基于 provider 组合工具，不再堆硬编码列表

## 当前真实可用功能

1. 同步聊天
2. 流式聊天
3. 异步聊天
4. Agent 决策与运行 trace
5. 写入/副作用/危险操作确认
6. 模型状态查看与切换
7. 会话历史查询
8. 长期语义记忆召回
9. 天气 / 汇率 / 新闻 / 工作区检索 / 代码分析 / 日志诊断等工具能力
10. 后台用户与角色管理
11. 审计日志查询
12. 飞书渠道接入
13. 模型 token 用量汇总与最近调用记录查看
14. runtime skill 列表与 Markdown skill 导入
15. Vue Agent Console 与 Admin Console

## 当前主要边界

1. 还没有通用知识库型 RAG
   - 当前更偏“会话记忆型 RAG + 工作区检索”。
2. 还没有完整 release/tag 策略
   - 当前工作分支仍是 `codex/bootstrap-github`。
3. 群聊总结还没有独立的自然日/时间范围聚合工具
   - 当前主要依赖群共享会话上下文。
4. Agent 动作确认还在本地产品化阶段
   - 已有风险分级、proposal、trace，但前端与后端的交互还可以继续打磨。
5. 当前工作区仍有未提交改动
   - 本文档按 2026-06-01 当前工作区状态更新，不等同于已发布版本。

## 最近一轮关键更新

1. Agent runtime 稳定化
   - 增加决策路由、动作 proposal、风险策略、运行 trace。
2. 工具治理继续收口
   - `ToolOrchestrator` 改为 provider 组合方式，便于扩展工具包。
3. Skill 执行边界更清晰
   - Python / builtin / prompt skill 通过统一 runtime 分发。
4. 前端控制台升级
   - Agent 页面加入运行 trace、动作确认、侧边栏体验和 GSAP 动效。
5. 安全与权限补强
   - Cookie 登录态、后台路由守卫、历史消息按用户过滤、DeepSeek tool-calling 兼容策略。
6. 本地无用产物清理
   - `.DS_Store`、Playwright 本地快照、Maven/Vite 构建产物属于可删除生成物，已由 `.gitignore` 覆盖。

## GitHub

- 仓库：[HhhBZzz/SpringClaw](https://github.com/HhhBZzz/SpringClaw)
- 当前分支：[codex/bootstrap-github](https://github.com/HhhBZzz/SpringClaw/tree/codex/bootstrap-github)
- 提交记录：[Commits](https://github.com/HhhBZzz/SpringClaw/commits/codex/bootstrap-github)
- 变更日志：[CHANGELOG.md](/Users/hanbingzheng/springclaw/CHANGELOG.md)

## 文档入口

1. [README.md](/Users/hanbingzheng/springclaw/README.md)
2. [INTERVIEW_PROJECT_BRIEF.md](/Users/hanbingzheng/springclaw/docs/INTERVIEW_PROJECT_BRIEF.md)
3. [ACCEPTANCE_CHECKLIST.md](/Users/hanbingzheng/springclaw/docs/ACCEPTANCE_CHECKLIST.md)
4. [RUN_REAL_ENVIRONMENT.md](/Users/hanbingzheng/springclaw/RUN_REAL_ENVIRONMENT.md)
5. [SCRIPT_SKILL_GUIDE.md](/Users/hanbingzheng/springclaw/docs/SCRIPT_SKILL_GUIDE.md)
