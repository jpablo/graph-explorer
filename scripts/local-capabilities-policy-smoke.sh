#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/debug/graph-explorer-desktop"

# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

desktop_pid=""
allowed_dir=""
blocked_dir=""

cleanup() {
  if [[ -n "${desktop_pid}" ]]; then
    kill "${desktop_pid}" >/dev/null 2>&1 || true
    wait "${desktop_pid}" >/dev/null 2>&1 || true
  fi
  [[ -n "${allowed_dir}" && -d "${allowed_dir}" ]] && rm -rf "${allowed_dir}"
  [[ -n "${blocked_dir}" && -d "${blocked_dir}" ]] && rm -rf "${blocked_dir}"
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

assert_contains() {
  local needle="$1"
  local haystack="$2"
  local label="$3"
  if [[ "${haystack}" != *"${needle}"* ]]; then
    echo "assertion failed for ${label}: expected to contain '${needle}'" >&2
    exit 1
  fi
}

require_cmd jq
require_cmd python3
require_cmd mktemp

if [[ ! -x "${DESKTOP_BIN}" ]]; then
  echo "building desktop binary..."
  (cd "${ROOT_DIR}/desktop/src-tauri" && cargo build)
fi

pkill -f graph-explorer-desktop >/dev/null 2>&1 || true
rm -f "${RUNTIME_FILE}"

allowed_dir="$(mktemp -d /tmp/gx-policy-allow-XXXXXX)"
blocked_dir="$(mktemp -d /tmp/gx-policy-block-XXXXXX)"
printf 'digraph G {\n  a -> b\n}\n' > "${allowed_dir}/ok.dot"
printf 'digraph G {\n  x -> y\n}\n' > "${blocked_dir}/nope.dot"

echo "starting desktop with GX_ALLOWED_ROOTS=${allowed_dir}"
GX_ALLOWED_ROOTS="${allowed_dir}" "${DESKTOP_BIN}" >/tmp/graph-explorer-desktop-policy-smoke.log 2>&1 &
desktop_pid=$!

echo "waiting for desktop control API..."
ready=0
if control_wait_ready 120; then ready=1; fi

if [[ "${ready}" -ne 1 ]]; then
  echo "desktop control API did not become ready" >&2
  exit 1
fi

status_raw="$(api_status)"
allowed_root_from_status="$(jq -r '.result.allowedRoots[0]' <<<"${status_raw}")"
expected_allowed_root="$(cd "${allowed_dir}" && pwd -P)"
assert_eq "${expected_allowed_root}" "${allowed_root_from_status}" "status allowed root"

echo "watch allowed path"
allowed_json="$(api_watch "$(cd "${allowed_dir}" && pwd -P)/ok.dot")"
assert_eq "ok" "$(api_last_status)" "allowed watch outcome"

# The old gate also asserted that `gx` mapped this refusal to exit 4 with code
# INVALID_REQUEST. That was a test of the CLI's error mapping, not of the
# desktop's policy, and it retired with the Rust gx -- the Scala one covers it
# in CliSpec ("a denied path is refused with exit 4 and recorded"). What the
# desktop does is asserted here, unchanged in substance.
echo "watch blocked path over the control channel (message should mention allowlist)"
blocked_json="$(api_watch "${blocked_dir}/nope.dot")"
assert_eq "error" "$(api_last_status)" "blocked watch outcome"
assert_eq "WATCH_FAILED" "$(jq -r '.error.code' <<<"${blocked_json}")" "blocked watch code"
assert_contains "outside configured allowlist" \
  "$(jq -r '.error.message' <<<"${blocked_json}")" "blocked watch message"

api_unwatch "$(cd "${allowed_dir}" && pwd -P)/ok.dot" >/dev/null

echo "policy smoke passed"
