#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/debug/graph-explorer-desktop"

# The gates drive the control API directly. `gx` is Scala now and speaks no
# HTTP (D2, D4), so routing the DESKTOP's tests through it would test a client
# we are replacing rather than the contract we are keeping.
# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

started_desktop=0
desktop_pid=""
tmpfile=""

cleanup() {
  if [[ -n "${tmpfile}" && -f "${tmpfile}" ]]; then
    rm -f "${tmpfile}"
  fi
  if [[ "${started_desktop}" -eq 1 && -n "${desktop_pid}" ]]; then
    kill "${desktop_pid}" >/dev/null 2>&1 || true
    wait "${desktop_pid}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required command: $1" >&2
    exit 1
  }
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "${expected}" != "${actual}" ]]; then
    echo "assertion failed for ${label}: expected '${expected}', got '${actual}'" >&2
    exit 1
  fi
}

assert_file_content_eq() {
  local expected="$1"
  local file_path="$2"
  local label="$3"
  local expected_file
  expected_file="$(mktemp /tmp/gx-phase3-expected-XXXXXX)"
  printf '%s' "${expected}" > "${expected_file}"
  if ! cmp -s "${expected_file}" "${file_path}"; then
    echo "assertion failed for ${label}: file content differs from expected bytes" >&2
    rm -f "${expected_file}"
    exit 1
  fi
  rm -f "${expected_file}"
}

require_cmd jq
require_cmd python3
require_cmd mktemp

if [[ ! -x "${DESKTOP_BIN}" ]]; then
  echo "building desktop binary..."
  (cd "${ROOT_DIR}/desktop/src-tauri" && cargo build)
fi

if ! control_ready; then
  echo "starting graph-explorer-desktop..."
  "${DESKTOP_BIN}" >/tmp/graph-explorer-desktop-phase3-smoke.log 2>&1 &
  desktop_pid=$!
  started_desktop=1
fi

echo "waiting for desktop control API..."
control_wait_ready 80 || exit 1

tmpfile="$(mktemp /tmp/gx-phase3-smoke-XXXXXX)"
printf 'digraph G {\n  a -> b\n}\n' > "${tmpfile}"
canonical_path="$(cd "$(dirname "${tmpfile}")" && pwd -P)/$(basename "${tmpfile}")"

echo "watching file: ${canonical_path}"
watch_json="$(api_watch "${canonical_path}")"
assert_eq "ok" "$(api_last_status)" "watch status"
watch_revision="$(jq -r '.result.revision' <<<"${watch_json}")"
assert_eq "1" "${watch_revision}" "watch revision"

get_initial="$(api_get "${canonical_path}")"
assert_eq "ok" "$(api_last_status)" "get status"
initial_revision="$(jq -r '.result.document.revision' <<<"${get_initial}")"
assert_eq "1" "${initial_revision}" "initial revision"

echo "simulating a UI write over the control channel (source=ui)"
ui_text=$'digraph G {\n  a -> c\n}\n'
ui_json="$(api_put "${canonical_path}" "${ui_text}" "${initial_revision}" ui)"
assert_eq "ok" "$(api_last_status)" "ui write status"
ui_revision="$(jq -r '.result.document.revision' <<<"${ui_json}")"
assert_eq "2" "${ui_revision}" "ui write revision"

assert_file_content_eq "${ui_text}" "${canonical_path}" "ui write file content"

echo "writing through the control API (source=cli)"
cli_text=$'digraph G {\n  c -> d\n}\n'
set_json="$(api_set "${canonical_path}" "${cli_text}" cli)"
assert_eq "ok" "$(api_last_status)" "cli write status"
cli_revision="$(jq -r '.result.document.revision' <<<"${set_json}")"
assert_eq "3" "${cli_revision}" "cli write revision"
assert_file_content_eq "${cli_text}" "${canonical_path}" "cli write file content"

echo "validating stale revision conflict"
stale_text=$'digraph G {\n  stale -> write\n}\n'
stale_json="$(api_put "${canonical_path}" "${stale_text}" 2 ui)"
# An error FRAME, not an unreachable channel: the desktop answered, and said no.
assert_eq "error" "$(api_last_status)" "stale write outcome"
assert_eq "DOCUMENT_CONFLICT" "$(jq -r '.error.code' <<<"${stale_json}")" "stale write code"
assert_eq "3" "$(jq -r '.error.currentRevision' <<<"${stale_json}")" "stale current revision"
assert_eq "2" "$(jq -r '.error.attemptedBaseRevision' <<<"${stale_json}")" "stale attempted base"

assert_file_content_eq "${cli_text}" "${canonical_path}" "stale write did not overwrite"

echo "cleaning up watch"
api_unwatch "${canonical_path}" >/dev/null

echo "phase3 smoke passed"
