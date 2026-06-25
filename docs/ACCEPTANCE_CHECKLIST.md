# 验收清单

> 说明：若本机设置了 `http_proxy/https_proxy`，请在 `curl` 命令中追加 `--noproxy '*'`，避免本地 `127.0.0.1` 请求被代理导致 502。
>
> 端口：默认 `18080`（见 `application.yml`）。如自定义请改为对应值。

## 1. 编译与测试

```bash
mvn -DskipTests compile
mvn test
```

期望：均成功。

## 2. 本地演示模式（无外部依赖）

```bash
OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run
```

说明：

- `springclaw.persistence.db-enabled=false`（默认；亦可用兼容回退环境变量 `SPRINGCLAW_PERSISTENCE_DB_ENABLED` / `OPENCLAW_PERSISTENCE_DB_ENABLED`）。
- MySQL / Redis / RabbitMQ 不可用时自动降级，不影响核心接口。
- 健康检查：`curl http://127.0.0.1:18080/actuator/health`。

## 3. 核心接口验收

### 3.1 同步问答

```bash
curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"你好","channel":"api"}'
```

期望：`code=0`，返回 answer。

### 3.2 流式问答

```bash
curl -N -X POST http://127.0.0.1:18080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"继续","channel":"api"}'
```

期望：返回 SSE，包含 `event:token` 等帧。

### 3.3 Webhook 统一入口

WebhookController 路径为 `/api/webhook/{channel}`，支持 `feishu`、`telegram`、`wechat` 等渠道：

```bash
curl -X POST http://127.0.0.1:18080/api/webhook/telegram \
  -H 'Content-Type: application/json' \
  -d '{"message":{"chat":{"id":100},"from":{"id":200},"text":"webhook hi"}}'
```

期望：`code=0`。

## 4. 生产模式切换

1. 配置真实 `OPENCLAW_PRIMARY_API_KEY`（亦可使用 `SPRINGCLAW_*` 等价变量）。
2. 设置 `springclaw.persistence.db-enabled=true`。
3. 执行 `src/main/resources/sql/schema.sql` 初始化 MySQL 表。
4. 配置可达的 MySQL / Redis（可选 RabbitMQ）。
5.（可选）开启 Webhook 安全：

```yaml
springclaw:
  webhook:
    security:
      enabled: true
      default-secret: your-secret
```

## 5. Webhook 安全头规范（开启后）

签名头沿用历史前缀 `X-Openclaw-*`（见 `DefaultWebhookSecurityService`）：

- `X-Openclaw-Timestamp`：秒级时间戳
- `X-Openclaw-Nonce`：随机串
- `X-Openclaw-Signature`：`hex(hmac_sha256(secret, timestamp + "\n" + nonce + "\n" + rawBody))`
