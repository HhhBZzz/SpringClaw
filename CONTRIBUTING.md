# Contributing to SpringClaw

Thanks for taking the time to improve SpringClaw. This project is a Spring Boot agent runtime, so changes should preserve observability, governance, and operational clarity.

## Development Setup

```bash
mvn -q -DskipTests compile

cd frontend
npm install
npm run build
```

For a local backend run:

```bash
OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run
```

For infrastructure-backed runs:

```bash
OPENCLAW_PRIMARY_API_KEY=test-key docker compose up -d --build
```

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
