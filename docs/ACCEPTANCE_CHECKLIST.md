# 验收清单

> 说明：若本机设置了 `http_proxy/https_proxy`，请在 `curl` 命令中追加 `--noproxy '*'`，避免本地 `127.0.0.1` 请求被代理导致 502。

## 1. 准备私有配置

从模板创建 `.env`，替换所有 MySQL、Redis 和 RabbitMQ 密码占位值；`.env` 不得提交。基础设施、原生后端和完整交付都使用这一份配置。

```bash
cp .env.example .env
```

模板默认禁用所有 AI provider。此状态下仍可验收服务健康和认证接口；聊天验收前，必须在 `.env` 中有意配置一个 provider。例如选择 primary 时，设置 `SPRINGCLAW_AI_ACTIVE_PROVIDER`、`SPRINGCLAW_PRIMARY_ENABLED`、`SPRINGCLAW_PRIMARY_API_KEY`、`SPRINGCLAW_PRIMARY_BASE_URL` 和 `SPRINGCLAW_PRIMARY_MODEL` 的实际值。不要在命令或文档中写入密钥。

## 2. 原生 Maven + Vite 验收

Docker 仅提供本机 MySQL、Redis 和 RabbitMQ；后端和前端分别在宿主机运行：

```bash
make dev-infra
make native-backend

# 在第二个终端运行
cd frontend
npm ci
npm run dev
```

后端默认监听 `127.0.0.1:18080`，Vite 默认在 `127.0.0.1:5173` 提供前端并代理 `/api`。保持 provider 禁用时可先验收健康和认证边界：

```bash
curl http://127.0.0.1:18080/actuator/health
curl -i http://127.0.0.1:18080/api/auth/me
```

期望：健康端点返回正常状态；未携带会话的认证请求返回 401 或 403。配置好 provider 后，再执行下面的聊天验收。

## 3. 聊天与 Webhook 接口验收

### 3.1 同步问答

```bash
curl -X POST http://127.0.0.1:18080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"你好","channel":"api"}'
```

期望：在已配置可用 provider 的前提下，`code=0` 并返回 answer。

### 3.2 流式问答

```bash
curl -N -X POST http://127.0.0.1:18080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"继续","channel":"api"}'
```

期望：在已配置可用 provider 的前提下，返回 SSE，包含 `event:token` 等帧。

### 3.3 Webhook 统一入口

WebhookController 路径为 `/api/webhook/{channel}`，支持 `feishu`、`telegram`、`wechat` 等渠道。公开入站 Webhook 必须在 `.env` 中设置 `SPRINGCLAW_WEBHOOK_SECURITY_ENABLED=true`，并按路由配置默认 `SPRINGCLAW_WEBHOOK_SECRET` 或渠道专用的 `SPRINGCLAW_WEBHOOK_SECRET_TELEGRAM`、`SPRINGCLAW_WEBHOOK_SECRET_WECHAT`、`SPRINGCLAW_WEBHOOK_SECRET_FEISHU`。变量说明见 [`.env.example`](../.env.example)，运行要求见 [运行与运维手册](../RUN_REAL_ENVIRONMENT.md)；不要将密钥写入请求示例或版本库。

开启签名校验前可用渠道测试负载验证路由：

```bash
curl -X POST http://127.0.0.1:18080/api/webhook/telegram \
  -H 'Content-Type: application/json' \
  -d '{"message":{"chat":{"id":100},"from":{"id":200},"text":"webhook hi"}}'
```

期望：未开启签名校验时，渠道请求返回 `code=0`。开启后，使用下述当前安全规范生成签名后再验收。

## 4. 完整 Compose 交付与数据库迁移

完整交付使用 Make 入口启动并验证 frontend、app、MySQL、Redis 和 RabbitMQ：

```bash
make up
make ps
make verify
```

`make verify` 先校验 Compose 配置和服务健康，再检查前端、Actuator 和认证代理；认证代理仅接受 200、401 或 403，因此无需配置 provider 也可完成这部分验收。

Flyway 是唯一的数据库迁移机制。正常的原生后端或 Compose app 启动会校验并按顺序应用迁移；不要手工执行数据库初始化脚本。基础设施凭据和端口均通过 `.env` 配置，并使用上述 Make 命令生效。

## 5. Webhook 安全头规范（开启后）

签名头沿用历史协议前缀 `X-Openclaw-*`（见 `DefaultWebhookSecurityService`）：

- `X-Openclaw-Timestamp`：秒级时间戳
- `X-Openclaw-Nonce`：随机串
- `X-Openclaw-Signature`：`hex(hmac_sha256(secret, timestamp + "\n" + nonce + "\n" + rawBody))`
