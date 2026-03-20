# 验收清单（2026-03-09）

> 说明：若本机设置了 `http_proxy/https_proxy`，请在 `curl` 命令中追加 `--noproxy '*'`，避免本地 `127.0.0.1` 请求被代理导致 502。

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
- `openclaw.persistence.db-enabled=false`（默认）
- MySQL/Redis 不可用时自动降级，不影响核心接口。

## 3. 核心接口验收

### 3.1 同步问答

```bash
curl -X POST http://127.0.0.1:8080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"你好","channel":"api"}'
```

期望：`code=0`，返回 answer。

### 3.2 流式问答

```bash
curl -N -X POST http://127.0.0.1:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"demo-1","userId":"u1","message":"继续","channel":"api"}'
```

期望：返回 SSE `event:token`。

### 3.3 Webhook 统一入口

```bash
curl -X POST http://127.0.0.1:8080/api/webhook/telegram \
  -H 'Content-Type: application/json' \
  -d '{"message":{"chat":{"id":100},"from":{"id":200},"text":"webhook hi"}}'
```

期望：`code=0`。

## 4. 生产模式切换

1. 配置真实 `OPENCLAW_PRIMARY_API_KEY`
2. `openclaw.persistence.db-enabled=true`
3. 执行 `src/main/resources/sql/schema.sql`
4. 配置 MySQL/Redis
5.（可选）开启 Webhook 安全：

```yaml
openclaw:
  webhook:
    security:
      enabled: true
      default-secret: your-secret
```

## 5. Webhook 安全头规范（开启后）

- `X-Openclaw-Timestamp`: 秒级时间戳
- `X-Openclaw-Nonce`: 随机串
- `X-Openclaw-Signature`: `hex(hmac_sha256(secret, timestamp + "\n" + nonce + "\n" + rawBody))`

## 6. 收口阶段真实验证报告

- [REAL_VERIFICATION_REPORT_2026-03-09.md](/Users/hanbingzheng/springclaw/docs/REAL_VERIFICATION_REPORT_2026-03-09.md)
