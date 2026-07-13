# SpringClaw 运行与运维手册

本手册面向单机自托管交付。它不覆盖 Kubernetes、多机高可用或自动证书签发；这些场景应在本手册稳定运行后再单独设计。

## 1. 运行模式与前置条件

准备 Docker Desktop（Docker Compose v2），在项目根目录执行命令。另需为 `.env` 中的基础设施密码选择安全且互不相同的值。

- **本地开发**：`make dev-infra` 只启动 MySQL、Redis、RabbitMQ；Maven 和 Vite 在宿主机启动。本地命令需要 Bash 兼容 shell；Windows 请使用 WSL。
- **完整交付**：`make up` 启动 frontend、app、MySQL、Redis、RabbitMQ 五项服务，适用于演示、验收和单机部署。

完整交付中只有 frontend 的 HTTP 端口映射到宿主机；app 和三项基础依赖保持在 Docker 内部网络。

## 2. 首次配置与启动

```bash
cp .env.example .env
# 编辑 .env，替换所有密码占位值后再继续
make up
make ps
make verify
```

`.env` 是单机交付的私有配置来源，不要提交或复制到镜像。至少设置 MySQL、Redis、RabbitMQ 的密码，并审核以下安全项：

- `SPRINGCLAW_ADMIN_USERNAMES`：逗号分隔的账号名；名单中的账号注册时会成为 `ADMIN`。生产环境保持 `SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN=false`，避免“第一个注册账号”成为管理员。
- `SPRINGCLAW_PASSWORD_PEPPER`：如启用，必须稳定保管；随意改变会让旧密码无法验证。
- `SPRINGCLAW_AUTH_COOKIE_SECURE`：本机纯 HTTP 仅可设为 `false`。若浏览器经 HTTPS/TLS 访问，必须设为 `true`。
- `SPRINGCLAW_HTTP_BIND_ADDRESS`：默认 `127.0.0.1`。对公网服务时，让 TLS 反向代理转发到此回环端口，不要直接以纯 HTTP 暴露登录入口。
- Webhook 签名安全：对每个公网可访问的入站 Webhook，必须设置 `SPRINGCLAW_WEBHOOK_SECURITY_ENABLED=true`，并配置对应的 `SPRINGCLAW_WEBHOOK_SECRET` 或渠道专用密钥；密钥不得记录或提交。
- AI provider 配置：模板默认关闭全部 provider。应用可以健康启动，但聊天前必须显式配置一个 provider 的 enabled/key/base-url/model。

`make verify` 是交付冒烟检查：它校验 Compose 配置，等待五项服务健康，检查前端首页，并确认 `/api/auth/me` 仅接受 200、401 或 403；该 API 检查不要求登录，未认证响应是可接受的。它还会从 app 容器内部检查 Actuator health。验证器默认使用 Docker Compose 解析 `.env` 后的 `SPRINGCLAW_HTTP_PORT`；仅在独立冒烟环境需要临时覆盖时才传入 `HTTP_PORT`。

## 3. 本地原生开发

```bash
make dev-infra
make native-backend

# 另开终端
cd frontend
npm ci
npm run dev
```

`make native-backend` 通过 Docker Compose 的已解析 JSON 启动 Maven，并且只传递明确的数据库、Redis、RabbitMQ 和文档列出的 SpringClaw 用户配置。它还会携带交付版本的持久化和飞书安全运行时设置。这样带引号的值、行尾注释、插值和多行值与完整 Compose 交付一致，同时不会执行 `.env` 内容；本机连接端口来自 `docker-compose.dev.yml` 的回环映射。此时 Vite 在 `http://127.0.0.1:5173` 提供前端，代理请求给本机 `http://127.0.0.1:18080`。`docker-compose.dev.yml` 仅把依赖端口绑定到回环地址，不应拿来作为正式公网交付文件。

## 4. 数据库迁移与健康状态

Flyway 是唯一的数据库迁移机制。应用在启动时会校验已执行的迁移，并按顺序应用缺失迁移；配置禁止 destructive clean。不要通过手动初始化脚本或删除卷来解决正常的升级问题。

健康链路由 Compose 控制：MySQL、Redis、RabbitMQ 各自有 healthcheck；app 会等待它们健康并以 `/actuator/health` 进行自检；frontend 只在 app 健康后启动。正式 profile 只暴露 health，不经前端代理公开 Actuator。

检查状态和日志：

```bash
make ps
make logs
docker compose --env-file .env logs -f --tail=200 app
docker compose --env-file .env logs -f --tail=200 mysql
```

如果 `make verify` 超时，先看 `make ps` 的 Health 列，再查看对应服务日志。常见原因是 `.env` 中遗留了占位密码、宿主机 HTTP 端口被占用，或者数据库卷来自不兼容的旧配置。

## 5. 备份

升级前先备份 MySQL。下面的命令在数据库容器内使用其私有 root 密码，因此不会把密码打印到终端：

```bash
mkdir -p backups
stamp=$(date +%F-%H%M%S)
docker compose --env-file .env exec -T mysql \
  sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --databases "$MYSQL_DATABASE"' \
  > "backups/springclaw-${stamp}.sql"
```

备份文件包含用户、会话、审计和其他 MySQL 业务数据。把它加密并保存到宿主机以外的位置。Redis、应用数据和 workspace 是命名卷；如其中保存了业务资产，请按你的数据保留策略对 Docker 卷做快照。

## 6. 恢复 MySQL 备份

恢复会改变当前数据。先停止完整栈（不会删除卷），仅启动 MySQL，导入备份，再启动完整栈并再次验证：

```bash
make down
docker compose --env-file .env up -d mysql
cat backups/springclaw-YYYY-MM-DD-HHMMSS.sql | \
  docker compose --env-file .env exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
make up
make verify
```

恢复旧版本数据后，下一次 app 启动会由 Flyway 继续执行缺失迁移；请保留 `make verify` 的成功结果再开放流量。

## 7. 升级

建议按以下顺序升级：

```bash
make verify
# 按上一节完成备份
git pull --ff-only
make up
make verify
```

`make down` 默认保留命名卷，`make up` 会重建有变化的镜像并启动服务。升级后检查 app 日志中的 Flyway 信息；正常情况下已有迁移会被校验，新迁移会按顺序执行。

## 8. 停止与破坏性清理

日常停止使用下列无损命令：

```bash
make down
```

**危险：以下命令会永久删除当前 Compose 项目的 MySQL、Redis、RabbitMQ、应用和 workspace 数据卷。只有在已验证备份并明确要从零开始时才执行。**

```bash
docker compose --env-file .env down -v
```

执行破坏性清理后，下一次 `make up` 是全新安装；Flyway 会重新创建并迁移数据库。
