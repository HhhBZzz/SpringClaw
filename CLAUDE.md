# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenClaw Java is an enterprise AI Agent backend built with Spring Boot 3.5 + Spring AI 1.1. It provides multi-model chat with provider failover, dual-track memory (MySQL event stream + Redis vector store), tool/skill governance via AOP, and Feishu (Lark) integration. Java 17, single-module Maven project.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Run locally (no real LLM needed — falls back to local skills)
OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run

# Run with Docker (MySQL + Redis + RabbitMQ + App)
OPENCLAW_PRIMARY_API_KEY=test-key docker compose up -d --build

# Health check (port 18080)
curl http://127.0.0.1:18080/actuator/health
```

## Testing

Tests are pure JUnit 5 + Mockito unit tests (no Spring context loading). 34 test files under `src/test/java/com/springclaw/`.

```bash
mvn test                                                    # All tests
mvn test -Dtest=ChatServiceImplModeTest                     # Single class
mvn test -Dtest=ChatServiceImplModeTest#shouldDefaultTo...  # Single method
mvn test -Dtest="com.springclaw.service.chat.**"              # Package pattern
```

No linter (Checkstyle/SpotBugs/PMD) is configured.

## Architecture

### Request Flow

1. **Controllers** (`controller/`) receive HTTP requests. Chat endpoints: `POST /api/chat/{send,stream,async}`.
2. **ChatServiceImpl** (`service/chat/impl/ChatServiceImpl.java`) is the master orchestrator. It delegates to one of two engines based on `ChatRoutingPolicyService`:
   - **SimplifiedOparEngine** — default, faster for simple queries
   - **OparLoopEngine** — full OPAR (Observe-Plan-Act-Reflect) loop for complex queries
3. **ModelCallExecutor** handles the actual LLM call with automatic provider failover via **AiProviderService** (`service/ai/`), which manages multiple providers (primary/qwen/coding-plan/deepseek).
4. Tools annotated with Spring AI `@Tool` in `tool/pack/` are dynamically injected. **ToolRuntimeAspect** (`tool/runtime/`) intercepts all tool calls via AOP for permission checking, rate limiting, and audit.

### Key Subsystems

- **Memory**: Dual-track — short-term via MySQL `message_event` table (`service/event/`), long-term via Redis Vector Store with embeddings (`service/memory/`). `ContextAssembler` (`service/context/`) builds the full conversation context including semantic recall.
- **Channel Adapters** (`strategy/channel/`): Strategy pattern — `ChannelAdapter` interface with `FeishuChannelAdapter`, `TelegramChannelAdapter`, `WechatChannelAdapter`, selected by `ChannelAdapterFactory`.
- **Skills** (`service/skill/`): Directory-based skills rooted at `skills/`. `SkillCatalogService` scans `SKILL.md` definitions. `SkillRuntimeService` executes `python` / historical `script` and `builtin` skills, while prompt-style markdown skills remain non-executable instructions. Governed by `skill_descriptor` and `skill_policy` entities.
- **Auth** (`web/auth/`): Token-based auth with `@RequireRole` annotation. `TokenAuthenticationInterceptor` + `RoleAuthorizationInterceptor`. First registered user auto-promoted to ADMIN.
- **Async Chat**: RabbitMQ-based — producer sends to queue, consumer processes, result stored in Redis with TTL. Poll via `GET /api/chat/async/{id}`.
- **System Prompt**: Loaded from `SOUL.md` at project root by `SoulPromptService`.

### Configuration

Almost everything is externalized via `application.yml` with `${OPENCLAW_*}` environment variable overrides. Key env vars:
- `OPENCLAW_PRIMARY_API_KEY` / `OPENCLAW_CODING_PLAN_API_KEY` / `OPENCLAW_DEEPSEEK_API_KEY` — LLM provider keys
- `OPENCLAW_EMBEDDING_API_KEY` + `OPENCLAW_EMBEDDING_MODEL` — embedding config
- `OPENCLAW_CHAT_AGENT_MODE` — `simplified` (default) or `opar`
- `OPENCLAW_FEISHU_*` — Feishu integration settings

Spring AI's auto-configured model beans are explicitly disabled (`spring.ai.model.chat: none`, etc.) — the project uses its own provider management via `springclaw.ai.providers.*`.

## Code Conventions

- **Constructor injection** throughout (no `@Autowired` on fields)
- **Java records** for DTOs (`ChatRequest`, `ChatResponse`, `AssembledContext`, etc.)
- **Unified API response**: All REST endpoints return `ApiResponse<T>` with `{code, message, data}`
- **Interface + Impl separation**: e.g., `ChatService` → `ChatServiceImpl`, `MemoryService` → `VectorMemoryService`
- **Lombok**: Used for `@Slf4j`, `@Data`, `@Builder`, `@RequiredArgsConstructor` etc.
- **Graceful degradation**: When the AI model is unavailable, the system falls back to local skill execution
- **Meta-guard**: `ChatResponsePolicyService` detects model identity/refusal leaks and retries automatically
