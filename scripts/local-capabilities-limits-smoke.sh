#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/debug/graph-explorer-desktop"

# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

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
    if control_ready; then
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
require_cmd python3
require_cmd mktemp

if [[ ! -x "${DESKTOP_BIN}" ]]; then
  echo "building desktop binary..."
  (cd "${ROOT_DIR}/desktop/src-tauri" && cargo build)
fi

echo "checking payload-size limit"
start_desktop 64 1000 10000
big_text="$(printf 'x%.0s' {1..400})"
payload_json="$(api_push_text "${big_text}")"
# An oversized FRAME now, not an oversized HTTP body: the limit moved with the
# transport, and so did its enforcement point -- a frame over the cap is refused
# before it is parsed, and the connection ends rather than trying to resynchronize
# a stream whose boundaries are no longer trustworthy.
assert_eq "error" "$(api_last_status)" "payload limit outcome"
assert_eq "PAYLOAD_TOO_LARGE" "$(jq -r '.error.code' <<<"${payload_json}")" "payload limit code"

kill "${desktop_pid}" >/dev/null 2>&1 || true
wait "${desktop_pid}" >/dev/null 2>&1 || true
desktop_pid=""

echo "checking request-rate limit"
# Still worth keeping, with a narrower job. The rate limit is no longer a
# defence against other local processes -- the socket's 0600 mode is that -- but
# a runaway agent in a loop is exactly the mistake D6 says guardrails catch.
start_desktop 1048576 3 5000

outcomes=()
for _ in 1 2 3 4; do
  frame="$(api_status || true)"
  if [[ "$(api_last_status)" == "error" ]]; then
    outcomes+=("$(jq -r '.error.code' <<<"${frame}")")
  else
    outcomes+=("$(api_last_status)")
  fi
done

assert_eq "ok" "${outcomes[0]}" "rate-limit request 1"
assert_eq "ok" "${outcomes[1]}" "rate-limit request 2"
assert_eq "RATE_LIMITED" "${outcomes[2]}" "rate-limit request 3"
assert_eq "RATE_LIMITED" "${outcomes[3]}" "rate-limit request 4"

echo "limits smoke passed"
