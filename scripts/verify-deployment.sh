#!/usr/bin/env bash
set -Eeuo pipefail

readonly ENV_FILE="${ENV_FILE:-.env}"
readonly HTTP_HOST="${HTTP_HOST:-127.0.0.1}"
readonly TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
readonly POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
readonly SERVICES=(mysql redis rabbitmq app frontend)

fail() {
  printf 'Deployment verification failed: %s\n' "$*" >&2
  exit 1
}

compose() {
  docker compose --env-file "$ENV_FILE" "$@"
}

env_file_value() {
  local expected_key="$1"
  local line
  local value=""
  local found=false

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" == "$expected_key="* ]]; then
      value="${line#*=}"
      found=true
    fi
  done < "$ENV_FILE"

  if [[ "$found" == true ]]; then
    printf '%s' "$value"
    return 0
  fi
  return 1
}

service_is_healthy() {
  local service="$1"
  local state

  state="$(compose ps --format json "$service" 2>/dev/null || true)"
  [[ "$state" == *'"State":"running"'* && "$state" == *'"Health":"healthy"'* ]]
}

[[ -f "$ENV_FILE" ]] || fail "environment file '$ENV_FILE' does not exist"
if [[ -z "${HTTP_PORT+x}" ]]; then
  HTTP_PORT="$(env_file_value SPRINGCLAW_HTTP_PORT || true)"
  HTTP_PORT="${HTTP_PORT:-8080}"
fi
readonly HTTP_PORT
[[ "$HTTP_PORT" =~ ^[1-9][0-9]{0,4}$ ]] && ((10#$HTTP_PORT <= 65535)) \
  || fail "HTTP_PORT must be an integer between 1 and 65535"
[[ "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "TIMEOUT_SECONDS must be a positive integer"
[[ "$POLL_INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "POLL_INTERVAL_SECONDS must be a positive integer"

compose config --quiet

deadline=$((SECONDS + TIMEOUT_SECONDS))
while true; do
  pending=()
  for service in "${SERVICES[@]}"; do
    if ! service_is_healthy "$service"; then
      pending+=("$service")
    fi
  done

  if ((${#pending[@]} == 0)); then
    break
  fi

  remaining=$((deadline - SECONDS))
  if ((remaining <= 0)); then
    compose ps
    fail "timed out waiting for healthy services: ${pending[*]}"
  fi

  printf 'Waiting for healthy services: %s\n' "${pending[*]}"
  sleep_seconds="$POLL_INTERVAL_SECONDS"
  if ((sleep_seconds > remaining)); then
    sleep_seconds="$remaining"
  fi
  sleep "$sleep_seconds"
done

curl --fail --silent --show-error "http://$HTTP_HOST:$HTTP_PORT/" >/dev/null
curl --silent --show-error "http://$HTTP_HOST:$HTTP_PORT/api/auth/me" >/dev/null || true
compose exec -T app curl --fail --silent --show-error http://127.0.0.1:18080/actuator/health >/dev/null

printf 'Deployment verification succeeded.\n'
