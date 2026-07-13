#!/usr/bin/env bash
set -Eeuo pipefail

readonly ENV_FILE="${ENV_FILE:-.env}"

fail() {
  printf 'Native backend startup failed: %s\n' "$*" >&2
  exit 1
}

compose() {
  docker compose --env-file "$ENV_FILE" "$@"
}

run_maven_with_compose_environment() {
  compose config --environment | BASH_ENV= bash --noprofile --norc -c '
    set -Eeuo pipefail

    while IFS= read -r assignment || [[ -n "$assignment" ]]; do
      [[ "$assignment" == *=* ]] || continue
      key="${assignment%%=*}"
      [[ "$key" =~ ^(MYSQL|REDIS|RABBITMQ|SPRINGCLAW)_[A-Z0-9_]+$ ]] || continue
      value="${assignment#*=}"
      export "$key=$value"
    done

    exec mvn spring-boot:run "$@"
  ' native-backend "$@"
}

[[ -f "$ENV_FILE" ]] || fail "environment file '$ENV_FILE' does not exist"
compose config --quiet
run_maven_with_compose_environment "$@"
