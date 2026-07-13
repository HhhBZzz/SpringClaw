#!/usr/bin/env bash
set -Eeuo pipefail

readonly ENV_FILE="${ENV_FILE:-.env}"
temporary_config_file=""

fail() {
  printf 'Native backend startup failed: %s\n' "$*" >&2
  exit 1
}

compose() {
  docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f docker-compose.dev.yml "$@"
}

cleanup() {
  if [[ -n "$temporary_config_file" ]]; then
    rm -f "$temporary_config_file"
  fi
}

trap cleanup EXIT
[[ -f "$ENV_FILE" ]] || fail "environment file '$ENV_FILE' does not exist"
compose config --quiet

umask 077
temporary_config_file="$(mktemp "${TMPDIR:-/tmp}/springclaw-native-config.XXXXXX")" \
  || fail "could not create a private Compose configuration file"
if ! compose config --format json > "$temporary_config_file"; then
  fail "could not resolve the development Compose configuration"
fi

node scripts/run-native-backend.mjs "$temporary_config_file" "$@"
