#!/usr/bin/env bash
set -Eeuo pipefail

case "$(basename "$0")" in
  curl)
    for argument in "$@"; do
      if [[ "$argument" == *"/api/auth/me" ]]; then
        printf '%s' "${FAKE_AUTH_STATUS:?FAKE_AUTH_STATUS is required}"
        exit "${FAKE_AUTH_EXIT:?FAKE_AUTH_EXIT is required}"
      fi
    done
    exit 0
    ;;
  docker)
    case " $* " in
      *" ps "*) printf '%s\n' '{"State":"running","Health":"healthy"}' ;;
    esac
    exit 0
    ;;
esac

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly VERIFIER="$REPOSITORY_ROOT/scripts/verify-deployment.sh"
readonly TEST_SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/springclaw-verify-test.XXXXXX")"
readonly temporary_directory
readonly environment_file="$(mktemp "$temporary_directory/environment.XXXXXX")"

cleanup() {
  rm -rf "$temporary_directory"
}

trap cleanup EXIT
mkdir "$temporary_directory/bin"
ln -s "$TEST_SCRIPT_PATH" "$temporary_directory/bin/curl"
ln -s "$TEST_SCRIPT_PATH" "$temporary_directory/bin/docker"

run_case() {
  local name="$1"
  local status="$2"
  local curl_exit="$3"
  local expected_exit="$4"
  local output_file="$temporary_directory/$name.output"
  local actual_exit

  set +e
  PATH="$temporary_directory/bin:$PATH" \
    ENV_FILE="$environment_file" \
    HTTP_PORT=18081 \
    FAKE_AUTH_STATUS="$status" \
    FAKE_AUTH_EXIT="$curl_exit" \
    "$VERIFIER" >"$output_file" 2>&1
  actual_exit=$?
  set -e

  if [[ "$actual_exit" != "$expected_exit" ]]; then
    printf 'case %s: expected exit %s, got %s\n' "$name" "$expected_exit" "$actual_exit" >&2
    sed -n '1,120p' "$output_file" >&2
    return 1
  fi
}

run_case accepted_401 401 0 0
run_case transport_failure 000 7 1
run_case unexpected_404 404 0 1

printf '%s\n' 'verify-deployment protected API regression tests passed.'
