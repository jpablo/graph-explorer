#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
GX_BIN="${ROOT_DIR}/gx/target/debug/gx"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/debug/graph-explorer-desktop"

desktop_pid=""

cleanup() {
  if [[ -n "${desktop_pid}" ]]; then
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

wait_for_desktop() {
  for _ in $(seq 1 120); do
    local status_json
    status_json="$("${GX_BIN}" status --json || true)"
    local running
    running="$(jq -r '.running // false' <<<"${status_json}")"
    if [[ "${running}" == "true" && -f "${RUNTIME_FILE}" ]]; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

start_desktop() {
  local max_body="$1"
  local max_requests="$2"
  local window_ms="$3"

  pkill -f graph-explorer-desktop >/dev/null 2>&1 || true
  rm -f "${RUNTIME_FILE}"

  GX_MAX_REQUEST_BODY_BYTES="${max_body}" \
  GX_RATE_LIMIT_MAX_REQUESTS="${max_requests}" \
  GX_RATE_LIMIT_WINDOW_MS="${window_ms}" \
    "${DESKTOP_BIN}" >/tmp/graph-explorer-desktop-limits-smoke.log 2>&1 &
  desktop_pid=$!

  if ! wait_for_desktop; then
    echo "desktop did not become ready for limits test" >&2
    exit 1
  fi
}

require_cmd jq
require_cmd curl
require_cmd mktemp

if [[ ! -x "${GX_BIN}" ]]; then
  echo "building gx binary..."
  (cd "${ROOT_DIR}/gx" && cargo build)
fi

if [[ ! -x "${DESKTOP_BIN}" ]]; then
  echo "building desktop binary..."
  (cd "${ROOT_DIR}/desktop/src-tauri" && cargo build)
fi

echo "checking payload-size limit"
start_desktop 64 1000 10000
port="$(jq -r '.port' "${RUNTIME_FILE}")"
token="$(jq -r '.token' "${RUNTIME_FILE}")"
big_text="$(printf 'x%.0s' {1..400})"
payload="$(jq -n --arg text "${big_text}" '{text: $text}')"
payload_response_file="$(mktemp /tmp/gx-limits-payload-XXXXXX.json)"
payload_status="$(
  curl -sS -o "${payload_response_file}" -w '%{http_code}' \
    -X POST "http://127.0.0.1:${port}/v1/push-text" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    --data "${payload}"
)"
assert_eq "413" "${payload_status}" "payload limit status"
payload_code="$(jq -r '.code' "${payload_response_file}")"
assert_eq "PAYLOAD_TOO_LARGE" "${payload_code}" "payload limit code"
rm -f "${payload_response_file}"

kill "${desktop_pid}" >/dev/null 2>&1 || true
wait "${desktop_pid}" >/dev/null 2>&1 || true
desktop_pid=""

echo "checking request-rate limit"
start_desktop 1048576 3 5000
port="$(jq -r '.port' "${RUNTIME_FILE}")"
token="$(jq -r '.token' "${RUNTIME_FILE}")"

status_codes=()
for i in 1 2 3 4; do
  response_file="$(mktemp /tmp/gx-limits-rate-${i}-XXXXXX.json)"
  code="$(
    curl -sS -o "${response_file}" -w '%{http_code}' \
      -H "Authorization: Bearer ${token}" \
      "http://127.0.0.1:${port}/v1/status"
  )"
  status_codes+=("${code}")
  if [[ "${code}" == "429" ]]; then
    rate_code="$(jq -r '.code' "${response_file}")"
    assert_eq "RATE_LIMITED" "${rate_code}" "rate-limit error code"
  fi
  rm -f "${response_file}"
done

assert_eq "200" "${status_codes[0]}" "rate-limit request 1"
assert_eq "200" "${status_codes[1]}" "rate-limit request 2"
assert_eq "429" "${status_codes[2]}" "rate-limit request 3"
assert_eq "429" "${status_codes[3]}" "rate-limit request 4"

echo "limits smoke passed"
