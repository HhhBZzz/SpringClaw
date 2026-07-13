# SpringClaw 可部署交付实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 将 SpringClaw 收敛为使用单一环境变量契约、可一键启动、可健康检查和可运维验证的单机 Docker 交付版本，同时保留 Maven/Vite 本地开发路径。

**Architecture:** docker-compose.yml 是不暴露内部依赖的完整交付拓扑；docker-compose.dev.yml 只映射开发端口。Vue 构建为 Nginx 容器并同源代理 /api 到 Spring Boot；Flyway、Actuator 与 Docker healthcheck 共同定义就绪状态。

**Tech Stack:** Docker Compose v2、MySQL 8.0.44、Redis 8.2.7、RabbitMQ 3.13.7、Nginx 1.31.2、Java 17/Spring Boot/Flyway、Vue/Vite、Maven/JUnit、Bash、Make。

## Global Constraints

- 不提交 .env、.env.local、真实密钥、真实密码或既有 Docker 卷。
- 基础 Compose 只映射前端 HTTP；内部依赖与 app 只能由开发覆盖文件映射至 127.0.0.1。
- 文档和示例只使用 SPRINGCLAW_*；不再出现 OPENCLAW_* 或 schema.sql。
- 应用使用 MYSQL_USER/MYSQL_PASSWORD；Flyway 必须 validate 且禁止 clean。
- 正式镜像不 bind mount 源码、skills 或 SOUL；使用命名数据卷和受限 /workspace。
- 冒烟使用独立 Compose 项目 springclaw-smoke 与临时 env，清理时只能删除该项目的卷。

---

## File Structure

| File | Responsibility |
| --- | --- |
| src/test/java/com/springclaw/config/DeploymentAssetPolicyTest.java | 锁定 Compose、环境模板和运行手册的交付契约。 |
| src/main/resources/application.yml、application-prod.yml | 数据库默认值、Flyway 和生产安全变量。 |
| Dockerfile | 后端镜像及 app 健康检查所需 curl。 |
| docker-compose.yml、docker-compose.dev.yml | 完整交付与本地基础设施覆盖。 |
| .env.example | 唯一、无密钥的配置模板。 |
| frontend/Dockerfile、frontend/nginx.conf | Vue 构建、SPA fallback 和 API/SSE 代理。 |
| Makefile、scripts/verify-deployment.sh | 统一启动入口和可重复冒烟。 |
| README.md、README_CN.md、RUN_REAL_ENVIRONMENT.md、CLAUDE.md | 启动、升级、备份和排障说明。 |

### Task 1: 建立会失败的部署资产契约

**Files:**
- Create: src/test/java/com/springclaw/config/DeploymentAssetPolicyTest.java

**Interfaces:** 只通过 Files.readString(Path.of(System.getProperty("user.dir"), relativePath)) 读取跟踪文件；不增加生产测试钩子。

- [ ] **Step 1: 写失败测试。**

~~~java
package com.springclaw.config;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class DeploymentAssetPolicyTest {
    @Test
    void releaseComposeHasAFrontendAndPinnedRuntimeImages() throws IOException {
        String compose = read("docker-compose.yml");
        assertThat(compose).contains("frontend:");
        assertThat(compose).contains("context: ./frontend");
        assertThat(compose).contains("mysql:8.0.44");
        assertThat(compose).contains("redis:8.2.7");
        assertThat(compose).contains("rabbitmq:3.13.7-management");
        assertThat(compose).doesNotContain("redis/redis-stack-server:latest");
        assertThat(compose).doesNotContain("MYSQL_PASSWORD: ${MYSQL_ROOT_PASSWORD");
    }

    @Test
    void developmentOverlayOwnsLoopbackInfrastructurePorts() throws IOException {
        String base = read("docker-compose.yml");
        String development = read("docker-compose.dev.yml");
        assertThat(base).doesNotContain("MYSQL_EXPOSED_PORT");
        assertThat(base).doesNotContain("REDIS_EXPOSED_PORT");
        assertThat(development).contains("127.0.0.1:${MYSQL_EXPOSED_PORT:-3306}:3306");
        assertThat(development).contains("127.0.0.1:${REDIS_EXPOSED_PORT:-6379}:6379");
        assertThat(development).contains("127.0.0.1:${RABBITMQ_EXPOSED_PORT:-5672}:5672");
    }

    @Test
    void examplesAndRunbooksUseCanonicalVariablesAndFlyway() throws IOException {
        String env = read(".env.example");
        String docs = read("README.md") + read("README_CN.md") + read("RUN_REAL_ENVIRONMENT.md");
        assertThat(env).contains("MYSQL_PASSWORD=").contains("REDIS_PASSWORD=");
        assertThat(env).contains("SPRINGCLAW_ADMIN_USERNAMES=");
        assertThat(env).doesNotContain("MYSQL_USER=root");
        assertThat(docs).doesNotContain("OPENCLAW_").doesNotContain("schema.sql");
        assertThat(docs).contains("Flyway").contains("make verify");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), relativePath));
    }
}
~~~

- [ ] **Step 2: 验证 RED。**

Run: mvn -Dtest=DeploymentAssetPolicyTest test

Expected: FAIL because frontend/release assets and development overlay do not yet exist.

- [ ] **Step 3: Commit.**

~~~bash
git add src/test/java/com/springclaw/config/DeploymentAssetPolicyTest.java
git commit -m "test: define deployment delivery contract"
~~~

### Task 2: 实现完整 Compose 与镜像边界

**Files:**
- Modify: Dockerfile, docker-compose.yml, .env.example
- Create: docker-compose.dev.yml, frontend/Dockerfile, frontend/nginx.conf

**Interfaces:**
- frontend can only reach app:18080 in the Compose network.
- app receives MYSQL_HOST=mysql, REDIS_HOST=redis, RABBITMQ_HOST=rabbitmq; it writes only /app/data and /workspace named volumes.
- Development overlay adds no credentials, only loopback port mappings.

- [ ] **Step 1: Replace release Compose with five health-gated services.**

The base file must contain these core contracts:

~~~yaml
mysql:
  image: mysql:8.0.44
  command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci", "--default-authentication-plugin=mysql_native_password"]
  environment:
    MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}
    MYSQL_DATABASE: ${MYSQL_DB:-springclaw}
    MYSQL_USER: ${MYSQL_USER:?MYSQL_USER is required}
    MYSQL_PASSWORD: ${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}
redis:
  image: redis:8.2.7
  command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASSWORD:?REDIS_PASSWORD is required}"]
rabbitmq:
  image: rabbitmq:3.13.7-management
app:
  build: .
  expose: ["18080"]
frontend:
  build:
    context: ./frontend
  ports: ["${SPRINGCLAW_HTTP_BIND_ADDRESS:-127.0.0.1}:${SPRINGCLAW_HTTP_PORT:-8080}:80"]
~~~

Add named volumes for MySQL, Redis, RabbitMQ, app data and workspace. MySQL, Redis and RabbitMQ each have a healthcheck; app depends on all three and checks /actuator/health; frontend depends on healthy app and checks its root response. Base Compose has no other ports mapping.

- [ ] **Step 2: Add the app environment contract.**

~~~yaml
SPRING_PROFILES_ACTIVE: prod
MYSQL_HOST: mysql
MYSQL_PORT: 3306
MYSQL_DB: ${MYSQL_DB:-springclaw}
MYSQL_USER: ${MYSQL_USER:?MYSQL_USER is required}
MYSQL_PASSWORD: ${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}
REDIS_HOST: redis
REDIS_PORT: 6379
REDIS_PASSWORD: ${REDIS_PASSWORD:?REDIS_PASSWORD is required}
RABBITMQ_HOST: rabbitmq
RABBITMQ_PORT: 5672
RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:?RABBITMQ_USERNAME is required}
RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:?RABBITMQ_PASSWORD is required}
SPRINGCLAW_PERSISTENCE_DB_ENABLED: "true"
SPRINGCLAW_AUTH_REDIS_ENABLED: "true"
SPRINGCLAW_LOCAL_FILES_ROOTS: /workspace
SPRINGCLAW_TOOL_FILE_ROOT: /workspace
SPRINGCLAW_TOOLS_LOCAL_WRITE_ROOT: /workspace
SPRINGCLAW_MEMORY_BANK_ROOT: /app/data/memory-bank
SPRINGCLAW_LEARNING_ROOT: /app/data/memory-bank
SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN: ${SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN:-false}
SPRINGCLAW_AUTH_COOKIE_SECURE: ${SPRINGCLAW_AUTH_COOKIE_SECURE:-false}
SPRINGCLAW_SYSTEM_COMMAND_ENABLED: ${SPRINGCLAW_SYSTEM_COMMAND_ENABLED:-false}
~~~

Pass current provider values only from environment; no real key or endpoint in Compose.

- [ ] **Step 3: Add development override.**

~~~yaml
services:
  mysql:
    ports: ["127.0.0.1:${MYSQL_EXPOSED_PORT:-3306}:3306"]
  redis:
    ports: ["127.0.0.1:${REDIS_EXPOSED_PORT:-6379}:6379"]
  rabbitmq:
    ports:
      - "127.0.0.1:${RABBITMQ_EXPOSED_PORT:-5672}:5672"
      - "127.0.0.1:${RABBITMQ_MANAGEMENT_EXPOSED_PORT:-15672}:15672"
~~~

- [ ] **Step 4: Build and proxy the frontend.**

frontend/Dockerfile uses Node 22 Alpine with npm ci and npm run build, then nginx:1.31.2-alpine. Nginx uses SPA fallback and SSE-friendly API proxy:

~~~nginx
location / { try_files $uri $uri/ /index.html; }
location /api/ {
  proxy_pass http://app:18080;
  proxy_http_version 1.1;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_buffering off;
  proxy_read_timeout 90s;
}
~~~

- [ ] **Step 5: Finish the no-secret template and app health dependency.**

Template uses MYSQL_USER=springclaw, non-default placeholder passwords, SPRINGCLAW_ADMIN_USERNAMES=admin, SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN=false, blank password pepper, explicit HTTP bind/port, and disabled-by-default providers. Runtime Docker stage installs curl:

~~~dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
~~~

- [ ] **Step 6: Verify GREEN and commit.**

~~~bash
mvn -Dtest=DeploymentAssetPolicyTest#releaseComposeHasAFrontendAndPinnedRuntimeImages+developmentOverlayOwnsLoopbackInfrastructurePorts test
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.dev.yml config --quiet
git add Dockerfile docker-compose.yml docker-compose.dev.yml .env.example frontend/Dockerfile frontend/nginx.conf
git commit -m "feat: package complete docker delivery"
~~~

Expected: the two Compose-asset policy methods and both Compose validations exit 0. The documentation policy method remains RED until Task 4 updates the runbooks.

### Task 3: 收敛 Spring 配置、Flyway 与生产安全

**Files:**
- Modify: src/main/resources/application.yml, src/main/resources/application-prod.yml
- Modify: src/test/java/com/springclaw/config/ApplicationYamlPolicyTest.java

- [ ] **Step 1: Add failing configuration policy.**

~~~java
@Test
void deploymentDefaultsUseSpringclawFlywayAndConfigurableWebhookSecurity() {
    Properties properties = applicationProperties();
    Assertions.assertTrue(properties.getProperty("spring.datasource.url").contains("${MYSQL_DB:springclaw}"));
    Assertions.assertEquals("true", properties.getProperty("spring.flyway.validate-on-migrate"));
    Assertions.assertEquals("true", properties.getProperty("spring.flyway.clean-disabled"));
    Assertions.assertEquals("${SPRINGCLAW_WEBHOOK_SECURITY_ENABLED:false}",
            properties.getProperty("springclaw.webhook.security.enabled"));
}
~~~

- [ ] **Step 2: Verify RED.**

Run: mvn -Dtest=ApplicationYamlPolicyTest#deploymentDefaultsUseSpringclawFlywayAndConfigurableWebhookSecurity test

Expected: FAIL because current datasource defaults to openclaw and Flyway/Webhook values are not explicit.

- [ ] **Step 3: Implement exact property changes.**

In application.yml, change fallback to ${MYSQL_DB:springclaw}, add validate-on-migrate: true and clean-disabled: true below spring.flyway, and set springclaw.webhook.security.enabled to ${SPRINGCLAW_WEBHOOK_SECURITY_ENABLED:false}.

In application-prod.yml set:

~~~yaml
springclaw:
  auth:
    bootstrap-first-user-admin: ${SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN:false}
    cookie:
      secure: ${SPRINGCLAW_AUTH_COOKIE_SECURE:true}
~~~

- [ ] **Step 4: Verify GREEN and commit.**

~~~bash
mvn -Dtest=ApplicationYamlPolicyTest,DeploymentAssetPolicyTest#releaseComposeHasAFrontendAndPinnedRuntimeImages+developmentOverlayOwnsLoopbackInfrastructurePorts test
git add src/main/resources/application.yml src/main/resources/application-prod.yml src/test/java/com/springclaw/config/ApplicationYamlPolicyTest.java
git commit -m "fix: align deployment configuration contract"
~~~

### Task 4: Add unified entry points and current operation docs

**Files:**
- Create: Makefile, scripts/verify-deployment.sh
- Modify: README.md, README_CN.md, RUN_REAL_ENVIRONMENT.md, CLAUDE.md

- [ ] **Step 1: Create the Make contract.**

~~~make
COMPOSE = docker compose --env-file .env
DEV_COMPOSE = $(COMPOSE) -f docker-compose.yml -f docker-compose.dev.yml
.PHONY: dev-infra up ps verify down logs
dev-infra:
	$(DEV_COMPOSE) up -d mysql redis rabbitmq
up:
	$(COMPOSE) up -d --build
ps:
	$(COMPOSE) ps
verify:
	./scripts/verify-deployment.sh
down:
	$(COMPOSE) down
logs:
	$(COMPOSE) logs -f --tail=200
~~~

- [ ] **Step 2: Create the deployment verifier.**

The script uses set -Eeuo pipefail; checks ENV_FILE; runs Compose config validation; polls five service states for 120 seconds; checks front HTML, proxy response, and internal actuator. It never prints env values or calls down -v.

~~~bash
curl --fail --silent --show-error "http://$HTTP_HOST:$HTTP_PORT/" >/dev/null
curl --silent --show-error "http://$HTTP_HOST:$HTTP_PORT/api/auth/me" >/dev/null || true
docker compose --env-file "$ENV_FILE" exec -T app curl --fail --silent --show-error http://127.0.0.1:18080/actuator/health
~~~

- [ ] **Step 3: Rewrite documentation.**

Both READMEs document cp .env.example .env, make dev-infra, native Maven/Vite run, make up, make verify. The runbook documents Flyway, HTTP/TLS Cookie setting, logs, backup, restore, upgrade and only explicitly-labelled down -v destruction. Update CLAUDE commands.

- [ ] **Step 4: Verify and commit.**

~~~bash
rg -n "OPENCLAW_|schema\\.sql" README.md README_CN.md RUN_REAL_ENVIRONMENT.md .env.example || true
cd frontend && npm test && npm run build
git add Makefile scripts/verify-deployment.sh README.md README_CN.md RUN_REAL_ENVIRONMENT.md CLAUDE.md
git commit -m "docs: publish reproducible deployment runbook"
~~~

Expected: no deprecated-document matches; frontend test and build exit 0.

### Task 5: Fresh Compose smoke, regression, commit and push

**Files:** no tracked file changes unless verification identifies a defect.

- [ ] **Step 1: Make a temporary ignored smoke env.**

Use mktemp, generate passwords, set MYSQL_DB=springclaw_smoke, MYSQL_USER=springclaw, SPRINGCLAW_HTTP_PORT=18081, disabled model providers, HTTP cookie false, and an explicit bootstrap admin. Do not write a real key.

- [ ] **Step 2: Start and verify isolated services.**

~~~bash
COMPOSE_PROJECT_NAME=springclaw-smoke docker compose --env-file "$SMOKE_ENV" -p springclaw-smoke up -d --build
COMPOSE_PROJECT_NAME=springclaw-smoke ENV_FILE="$SMOKE_ENV" HTTP_PORT=18081 ./scripts/verify-deployment.sh
~~~

Expected: five services running/healthy, front HTML reachable, app health UP, Flyway current.

- [ ] **Step 3: Prove restart/upgrade idempotency.**

~~~bash
COMPOSE_PROJECT_NAME=springclaw-smoke docker compose --env-file "$SMOKE_ENV" -p springclaw-smoke restart app
COMPOSE_PROJECT_NAME=springclaw-smoke docker compose --env-file "$SMOKE_ENV" -p springclaw-smoke logs --no-log-prefix app
~~~

Expected: app returns healthy and Flyway says schema is up to date; no volume deletion.

- [ ] **Step 4: Run full regressions with smoke DB coordinates.**

~~~bash
mvn test
cd frontend && npm test && npm run build
docker compose --env-file "$SMOKE_ENV" config --quiet
docker compose --env-file "$SMOKE_ENV" -f docker-compose.yml -f docker-compose.dev.yml config --quiet
~~~

Before Maven export smoke MySQL host/port/user/password/database variables; do not reuse .env.local.

- [ ] **Step 5: Clean only smoke resources, review, commit and push.**

~~~bash
COMPOSE_PROJECT_NAME=springclaw-smoke docker compose --env-file "$SMOKE_ENV" -p springclaw-smoke down -v
rm -f "$SMOKE_ENV"
git status --short
git add Dockerfile docker-compose.yml docker-compose.dev.yml .env.example frontend/Dockerfile frontend/nginx.conf Makefile scripts src/main/resources src/test/java/com/springclaw/config README.md README_CN.md RUN_REAL_ENVIRONMENT.md CLAUDE.md docs/superpowers/specs/2026-07-13-deployment-delivery-design.md docs/superpowers/plans/2026-07-13-deployment-delivery.md
git commit -m "feat: deliver reproducible springclaw deployment"
git push -u origin codex/deployment-release
~~~

Only stage delivery files; never stage .env, .env.local, target/, frontend/dist/, .worktrees/, .claude/worktrees/, or .tmp_interview_doc/.

## Plan Self-Review

- Spec coverage is complete: Task 1 locks assets, Task 2 implements complete Compose/frontend/config template, Task 3 aligns Spring/Flyway/security, Task 4 supplies commands/docs, Task 5 verifies fresh start, restart, regressions and push.
- Every task names exact paths, properties, commands and expected results.
- SPRINGCLAW_HTTP_PORT, ENV_FILE, COMPOSE_PROJECT_NAME, MYSQL_*, /workspace, app:18080, make verify and springclaw-smoke are used consistently.
