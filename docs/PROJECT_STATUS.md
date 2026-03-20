# PROJECT_STATUS

## 截至 2026-03-20 的结论

项目已经完成第一轮“可持续运行版”收敛。

它现在不是单纯的校招问答 Demo，而是一个可运行的企业内 Agent 后端，已经具备：

1. 多模型运行时
2. 短期/长期记忆
3. 认证、角色、后台
4. 飞书接入
5. GitHub 持续记录

## 当前已完成范围

### 1. 聊天与 Agent

- `POST /api/chat/send`
- `POST /api/chat/stream`
- `POST /api/chat/async`
- 默认模式：`simplified`
- 可选模式：`opar`

### 2. 模型能力

已接入：
- `coding-plan`
- `deepseek`

已支持：
- provider/model 查询
- provider/model 切换
- 模型失败后的同 provider 切换与降级

### 3. 记忆系统

短期：
- MySQL `message_event`
- MySQL `agent_session`

长期：
- Redis Vector Store
- embedding：`text-embedding-v4`

当前状态：
- 已不是“本地回退为主”
- 主实例已验证可以写入 Redis 向量索引并召回

### 4. 权限与后台

已完成：
- 注册 / 登录 / token
- `ADMIN only` 后台
- 工具权限控制
- 技能策略控制
- 用户、角色、模型、缓存、记忆、审计、活跃 token 会话管理

后台入口：
- `/admin`

### 5. 飞书接入

已完成：
- webhook 接入
- 长连接接入
- 飞书回消息
- 飞书私聊/群聊作用域区分

当前策略：
- 私聊保留个人连续记忆
- 群聊保留群共享上下文
- 群聊不再补召回用户私聊或跨场景个人长期记忆

### 6. 工具与 Skill

当前主要工具包：
- `system`
- `file`
- `workspace`
- `web`
- `weather`
- `exchange`
- `news`
- `script`

Skill 本质上是：
- 工具包能力 + 可见性策略 + 执行权限

## 当前真实可用功能

1. 同步聊天
2. 异步聊天
3. 模型状态查看与切换
4. 会话历史查询
5. 长期语义记忆召回
6. 天气 / 汇率 / 新闻 / 工作区检索等工具能力
7. 后台用户与角色管理
8. 审计日志查询
9. 飞书渠道接入

## 当前主要边界

1. 还没有通用知识库型 RAG
   - 当前更偏“会话记忆型 RAG”
2. 还没有滚动会话摘要层
   - 长对话体验仍主要依赖窗口 + 语义召回
3. GitHub 已接入，但当前工作分支还是 `codex/bootstrap-github`
   - 还没做 release/tag 策略
4. 群聊总结现在依赖群共享会话上下文
   - 还没做按自然日/按时间范围的独立总结工具

## 最近一轮关键更新

1. 默认 Agent 模式从 `opar` 收敛为 `simplified`
2. 后台改成 `ADMIN only`
3. 长期记忆切到真实 Redis 向量存储
4. 飞书群聊记忆边界修正：保留群共享，切断私聊污染
5. 项目初始化 git，并同步到 GitHub

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
