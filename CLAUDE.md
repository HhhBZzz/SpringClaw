# SpringClaw contributor guide

## Project overview

SpringClaw is a Java 17, single-module Spring Boot 3.5 application with Spring AI, MySQL, Redis, RabbitMQ, Flyway, and a Vue 3/Vite operations console. Tool calls are governed by runtime permission, risk, confirmation, and audit controls.

## Supported run commands

Create `.env` from `.env.example` before starting any runtime. `.env` is private and must not be committed.

```bash
cp .env.example .env

# Native development: start only Docker dependencies, then host processes.
make dev-infra
make native-backend
cd frontend && npm ci && npm run dev

# Complete delivery: frontend, backend, MySQL, Redis, and RabbitMQ.
make up
make ps
make verify

# Diagnose or stop while preserving named volumes.
make logs
make down
```

`make verify` validates Compose, waits for five healthy services, checks the frontend and API proxy, and performs an internal app Actuator health check. It uses Docker Compose's resolved `SPRINGCLAW_HTTP_PORT` from `.env` by default; an isolated caller can override the file with `ENV_FILE=/path/to/file` or intentionally override the probe port with `HTTP_PORT=...`. Preserve `COMPOSE_PROJECT_NAME` when invoking it for a separate Compose project.

`make native-backend` validates the Compose development overlay, resolves it as JSON, and starts Maven with only selected database, Redis, RabbitMQ, and documented SpringClaw settings. It uses loopback ports from the resolved development mappings, does not source or evaluate `.env`, preserves `PATH`/`HOME`, and never forwards arbitrary container-only settings.

## Deployment configuration

- `SPRINGCLAW_ADMIN_USERNAMES` is the comma-separated admin bootstrap allowlist. Production should keep first-user bootstrap disabled.
- Use `SPRINGCLAW_AUTH_COOKIE_SECURE=false` only for local HTTP. A TLS-terminating deployment must set it to `true`.
- Feishu delivery is disabled by default. To enable outbound delivery, set `SPRINGCLAW_FEISHU_OUTBOUND_ENABLED=true` and configure `SPRINGCLAW_FEISHU_APP_ID`, `SPRINGCLAW_FEISHU_APP_SECRET`, `SPRINGCLAW_FEISHU_VERIFICATION_TOKEN`, and `SPRINGCLAW_FEISHU_ENCRYPT_KEY`. To enable the long connection, also set `SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED=true` and `SPRINGCLAW_FEISHU_DOMAIN`; never log or commit credentials.
- Flyway validates and applies migrations at application startup; destructive clean is disabled.
- The release Compose topology exposes only the Nginx frontend. The development override is the only place infrastructure ports are published to host loopback.
- See [RUN_REAL_ENVIRONMENT.md](./RUN_REAL_ENVIRONMENT.md) for backup, restore, upgrade, log, and cleanup procedures.

## Testing

```bash
mvn test
mvn test -Dtest=DeploymentAssetPolicyTest
cd frontend && npm test && npm run build
docker compose --env-file .env config --quiet
make verify
```

## Architecture map

1. Controllers in `controller/` expose REST, SSE, auth, admin, and webhook endpoints.
2. `ChatServiceImpl` delegates through the simplified or OPAR runtime engines.
3. Provider routing and failover live in `service/ai/`; governed Spring AI tools live under `tool/`.
4. MySQL stores durable event and business data; Redis supplies cache, auth/session support, and vector memory; RabbitMQ handles asynchronous chat work.
5. `frontend/` is a Vue 3 console. Its release image is built separately and served by Nginx, which proxies `/api` to `app:18080`.

## Code conventions

- Prefer constructor injection and keep controller responses in `ApiResponse<T>`.
- Preserve permission checks, risk classification, confirmation proposals, and audit records around tool execution.
- New behavior requires a focused test that first fails, followed by relevant regression tests.
- Do not put credentials, private `.env` files, generated `target/`, or `frontend/dist/` into commits.
