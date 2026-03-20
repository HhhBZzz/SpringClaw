# RUN_REAL_ENVIRONMENT

目标：不用本机安装 MySQL/Redis，也能跑完整版（Docker）。

## 1. 前置条件

- 已安装 Docker Desktop
- 当前目录在项目根目录：`/Users/hanbingzheng/springclaw`

## 2. 一键启动（推荐）

```bash
# 不配置真实 key 也可跑，AI 将走本地技能/降级链路
OPENCLAW_PRIMARY_API_KEY=test-key docker compose up -d --build
```

查看状态：

```bash
docker compose ps
docker compose logs -f app
```

说明：
- `mysql`：自动初始化 `src/main/resources/sql/schema.sql`
- `redis`：使用 Redis Stack（带 RediSearch），用于向量记忆/锁/限流（按开关生效）
- `app`：Spring Boot 应用（端口 `18080`）

## 3. 验证接口

```bash
curl http://127.0.0.1:18080/actuator/health

curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"real-1","userId":"u1","message":"你好，介绍一下你能做什么","channel":"api"}'

curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"real-2","userId":"u1","message":"查北京天气","channel":"api"}'
```

## 4. 切换到真实大模型

```bash
export OPENCLAW_PRIMARY_API_KEY=your_real_key
export OPENCLAW_PRIMARY_BASE_URL=your_openai_compatible_base_url
export OPENCLAW_PRIMARY_MODEL=your_model_name
docker compose up -d --build
```

如果出现 TLS/握手失败，优先检查 `OPENCLAW_PRIMARY_BASE_URL` 的稳定性。

## 5. 不改 yml 的配置方式（全环境变量）

常用开关：

```bash
# 开启 DB 持久化（compose 默认已开）
export OPENCLAW_PERSISTENCE_DB_ENABLED=true

# 开启 Webhook 签名校验（默认 false）
export OPENCLAW_WEBHOOK_SECURITY_ENABLED=true

# 可选：飞书主动回消息
export OPENCLAW_FEISHU_OUTBOUND_ENABLED=true
export OPENCLAW_FEISHU_APP_ID=cli_xxx
export OPENCLAW_FEISHU_APP_SECRET=xxx

# 可选：飞书长连接（和 webhook 二选一）
export OPENCLAW_FEISHU_LONG_CONNECTION_ENABLED=true
```

## 6. 飞书接入测试（本地）

URL 验证：

```bash
curl -X POST http://127.0.0.1:18080/api/webhook/feishu \
  -H 'Content-Type: application/json' \
  -d '{"type":"url_verification","challenge":"test_challenge_value"}'
```

消息事件：

```bash
curl -X POST http://127.0.0.1:18080/api/webhook/feishu \
  -H 'Content-Type: application/json' \
  -d '{
    "event": {
      "sender": {"sender_id": {"open_id": "ou_test_user"}},
      "message": {
        "chat_id": "oc_test_chat",
        "message_type": "text",
        "content": "{\"text\":\"你好，飞书机器人\"}"
      }
    }
  }'
```

## 7. 常见问题

- 端口冲突：修改 `docker-compose.yml` 的端口映射。
- MySQL schema 没刷：首次启动才执行初始化脚本；重刷可用：

```bash
docker compose down -v
docker compose up -d --build
```

- 停止与清理：

```bash
docker compose down
```
