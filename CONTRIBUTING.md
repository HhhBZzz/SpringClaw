# Contributing to SpringClaw

Thanks for taking the time to improve SpringClaw. This project is a Spring Boot agent runtime, so changes should preserve observability, governance, and operational clarity.

## Development Setup

Create a private local configuration file, then replace every MySQL, Redis, and RabbitMQ password placeholder in it. Do not commit `.env`.

```bash
cp .env.example .env
```

For native backend and frontend development, use Docker only for local infrastructure. Maven and Vite use the same `.env` configuration:

```bash
make dev-infra
make native-backend

# In a second terminal
cd frontend
npm ci
npm run dev
```

Providers are disabled by default, so health and authentication work can be developed without provider credentials. To exercise chat, configure `SPRINGCLAW_AI_ACTIVE_PROVIDER` and the selected provider's current `SPRINGCLAW_*_ENABLED`, `SPRINGCLAW_*_API_KEY`, `SPRINGCLAW_*_BASE_URL`, and `SPRINGCLAW_*_MODEL` settings in `.env`.

To validate the complete delivered stack rather than the native development path:

```bash
make up
make verify
```

`make verify` validates the resolved Compose configuration, waits for the delivery services, and accepts only HTTP 200, 401, or 403 from the protected authentication route.

## Contribution Guidelines

- Keep pull requests focused on one behavior or documentation improvement.
- Open an issue before large runtime, memory, tool-governance, or schema changes.
- Prefer SpringClaw's existing patterns: constructor injection, records for DTOs, `ApiResponse<T>` for REST responses, and interface/implementation separation.
- Do not bypass tool governance. New executable capabilities should go through tool packs, the skill runtime, or the existing permission model.
- Add tests for behavior changes and include the exact command you ran in the PR description.
- Avoid committing local runtime data, logs, API keys, generated frontend builds, or IDE files.

## Useful Commands

```bash
mvn test
mvn -q -DskipTests compile
npm --prefix frontend run build
git diff --check
```

## Pull Request Checklist

- [ ] The change has a clear user or maintainer benefit.
- [ ] Runtime behavior is covered by tests when behavior changes.
- [ ] Documentation is updated when configuration, APIs, or operations change.
- [ ] No secrets, local data, logs, or generated build artifacts are committed.
- [ ] The PR description includes verification commands and outcomes.
