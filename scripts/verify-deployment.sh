#!/usr/bin/env bash
set -Eeuo pipefail

readonly ENV_FILE="${ENV_FILE:-.env}"
readonly HTTP_HOST="${HTTP_HOST:-127.0.0.1}"
readonly TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
readonly POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
readonly SERVICES=(mysql redis rabbitmq app frontend)
temporary_environment_file=""

fail() {
  printf 'Deployment verification failed: %s\n' "$*" >&2
  exit 1
}

compose() {
  docker compose --env-file "$ENV_FILE" "$@"
}

cleanup() {
  if [[ -n "$temporary_environment_file" ]]; then
    rm -f "$temporary_environment_file"
  fi
}

resolve_http_port() {
  local port_key_state

  umask 077
  temporary_environment_file="$(mktemp "${TMPDIR:-/tmp}/springclaw-verify-environment.XXXXXX")" \
    || fail "could not create a private Compose environment file"

  if ! compose config --environment > "$temporary_environment_file"; then
    fail "could not resolve the Compose environment"
  fi

  port_key_state="$(awk '
    index($0, "SPRINGCLAW_HTTP_PORT=") == 1 {
      found = 1
      exit
    }
    END { print(found ? "present" : "missing") }
  ' "$temporary_environment_file")" || fail "could not read the resolved Compose port"

  if [[ "$port_key_state" == "present" ]]; then
    HTTP_PORT="$(awk '
    index($0, "SPRINGCLAW_HTTP_PORT=") == 1 {
      value = substr($0, length("SPRINGCLAW_HTTP_PORT=") + 1)
      print value
      exit
    }
  ' "$temporary_environment_file")" || fail "could not read the resolved Compose port"
  else
    HTTP_PORT=8080
  fi
}

service_is_healthy() {
  local service="$1"
  local state

  state="$(compose ps --format json "$service" 2>/dev/null || true)"
  [[ "$state" == *'"State":"running"'* && "$state" == *'"Health":"healthy"'* ]]
}

verify_protected_api() {
  local status

  if ! status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      "http://$HTTP_HOST:$HTTP_PORT/api/auth/me")"; then
    fail "could not reach protected API endpoint"
  fi

  case "$status" in
    200|401|403)
      printf 'Protected API verification accepted HTTP %s.\n' "$status"
      ;;
    *)
      fail "protected API endpoint returned unexpected HTTP status $status"
      ;;
  esac
}

trap cleanup EXIT
[[ -f "$ENV_FILE" ]] || fail "environment file '$ENV_FILE' does not exist"
compose config --quiet
if [[ -z "${HTTP_PORT+x}" ]]; then
  resolve_http_port
fi
readonly HTTP_PORT
[[ "$HTTP_PORT" =~ ^[1-9][0-9]{0,4}$ ]] && ((10#$HTTP_PORT <= 65535)) \
  || fail "HTTP_PORT must be an integer between 1 and 65535"
[[ "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "TIMEOUT_SECONDS must be a positive integer"
[[ "$POLL_INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "POLL_INTERVAL_SECONDS must be a positive integer"

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
verify_protected_api
compose exec -T app curl --fail --silent --show-error http://127.0.0.1:18080/actuator/health >/dev/null

printf 'Deployment verification succeeded.\n'
