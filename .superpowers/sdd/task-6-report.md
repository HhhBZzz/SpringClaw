# Task 6 — security and reproducibility convergence report

## Scope and isolation

- Worked only in `/Users/hanbingzheng/springclaw/.worktrees/deployment-release` on `codex/deployment-release`.
- No `.env`, `.env.local`, existing Docker Compose resources, `.claude/worktrees/`, or `.tmp_interview_doc/` resources were changed.
- Compose validation used a private mode-`0600` temporary copy of `.env.example`; no configuration values were printed.
- Docker verification built only an untagged image and removed that image after the build. No services or Compose projects were started.

## TDD evidence

### RED

Added policy assertions before delivery-file changes, then ran:

```bash
mvn -Dtest=ApplicationYamlPolicyTest,DeploymentAssetPolicyTest test
```

The pre-change run failed with five intended policy failures:

1. `webhookSecurityDeliveryContractIsExplicitAndSecretSafe` — the Compose app environment lacked all five webhook substitutions.
2. `nativeLauncherForwardsTheDocumentedWebhookSecuritySettings` — the native launcher did not forward the webhook settings.
3. `dockerfilePinsBothJavaBaseImagesToImmutableDigests` — both Java `FROM` lines used mutable tags.
4. `verificationDocumentationLimitsProtectedApiResponsesToExpectedStatuses` — documentation said an arbitrary HTTP response was accepted.
5. `bootstrapAdminIsConfigurableForNativeDevelopmentAndDisabledByDefaultInProduction` — the base YAML used a literal `true` instead of the required environment placeholder.

The production-profile false fallback assertion was already satisfied; the failing base assertion proved the missing native-launch behavior.

### GREEN

Implemented only the requested delivery contract:

- Compose explicitly forwards `SPRINGCLAW_WEBHOOK_SECURITY_ENABLED` and all four webhook secret variables with variable substitutions, never literal credentials.
- `.env.example` supplies safe `false`/empty webhook defaults; English and Chinese READMEs plus the runbook require signature verification and appropriate secrets for every public inbound webhook, and prohibit logging or committing them.
- The native launcher forwards all five webhook settings after Compose resolves quoted/interpolated `.env` semantics.
- Base `application.yml` uses `${SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN:true}`; `application-prod.yml` retains its `false` fallback.
- Both Java Docker base images use the reviewed manifest-list digests.
- All three documentation surfaces accurately state that `make verify` accepts only 200, 401, or 403 from `/api/auth/me`.

The focused policy suite then passed:

```text
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

## Verification evidence

| Check | Result |
| --- | --- |
| `mvn -Dtest=ApplicationYamlPolicyTest,DeploymentAssetPolicyTest test` | Passed: 13 tests, 0 failures/errors/skips. |
| `bash scripts/test-verify-deployment.sh` | Passed: protected API regression cases accepted 401, rejected transport failure, and rejected 404. |
| `bash -n scripts/verify-deployment.sh scripts/run-native-backend.sh` | Passed. |
| `node --check scripts/run-native-backend.mjs` | Passed. |
| Base Compose `config --quiet` using private template environment | Passed. |
| Base plus development-overlay Compose `config --quiet` using private template environment | Passed. |
| `cd frontend && npm test && npm run build` | Passed: 6 files / 27 tests; Vue type-check and production build completed. |
| Clean-proxy Docker build | Passed. Both exact Maven and Temurin digests resolved; the no-cache build completed dependency resolution in 234.3 seconds, and the follow-up `--pull` build confirmed cached `mvn -q -DskipTests package` plus `COPY --from=builder /app/target/springclaw-0.0.1-SNAPSHOT.jar ./app.jar`, exported the final image, and removed its untagged image. |

## Commit scope

The commit contains only Task 6 delivery configuration, policy tests, documentation, and this evidence report. No push was performed.
