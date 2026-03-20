# REAL_VERIFICATION_REPORT_2026-03-09

## 目标

在“冻结新功能”前提下，完成一次真实可复现的运行验收，确认：

1. 应用可启动
2. 核心 API 可用
3. `agent_session` / `message_event` 可真实写入 MySQL

## 验证前提

1. 使用本机临时 MySQL 8.0（`127.0.0.1:3406`）
2. 应用启动参数：
   - `openclaw.persistence.db-enabled=true`
   - `openclaw.memory.vector-enabled=false`
   - `openclaw.guard.redis-enabled=false`
   - `openclaw.tools.guard.redis-enabled=false`
   - `OPENAI_API_KEY=test-key`
3. 验证端口：`18089`

## 验证请求与结果

1. `GET /actuator/health` -> `200`
2. `POST /api/chat/send` -> `200`
3. `POST /api/webhook/telegram` -> `200`

## 数据库验收结果

执行 SQL（摘要）：

```sql
SELECT COUNT(*) FROM openclaw.agent_session
WHERE session_key IN ('s-db-verify-001', 'telegram:888001');

SELECT COUNT(*) FROM openclaw.message_event
WHERE session_key IN ('s-db-verify-001', 'telegram:888001');
```

结果：

1. `agent_session_count = 2`
2. `message_event_count = 9`

抽样记录（摘要）：

1. `s-db-verify-001` 会话写入 `USER/ASSISTANT/SYSTEM(OPAR)` 事件
2. `telegram:888001` 会话写入 `USER/ASSISTANT/SYSTEM(OPAR)/SYSTEM(WEBHOOK)` 事件

## 结论

本次收口验证已证明：

1. 项目不是“仅本地内存演示”，而是可完成真实 HTTP + MySQL 事件流落库
2. 在未配置真实大模型 Key 时，系统仍保持 OPAR 结构和可审计事件写入（降级模式）
3. 具备校招演示所需的“可跑、可查、可解释”三要素
