#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
GX_BIN="${ROOT_DIR}/gx/target/debug/gx"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/debug/graph-explorer-desktop"

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

status_json="$("${GX_BIN}" status --json || true)"
running="$(jq -r '.running // false' <<<"${status_json}")"

if [[ "${running}" != "true" ]]; then
  echo "starting graph-explorer-desktop..."
  "${DESKTOP_BIN}" >/tmp/graph-explorer-desktop-phase3-smoke.log 2>&1 &
  desktop_pid=$!
  started_desktop=1
fi

echo "waiting for desktop control API..."
ready=0
for _ in $(seq 1 80); do
  if [[ -f "${RUNTIME_FILE}" ]]; then
    port="$(jq -r '.port' "${RUNTIME_FILE}")"
    token="$(jq -r '.token' "${RUNTIME_FILE}")"
    if [[ "${port}" != "null" && "${token}" != "null" ]]; then
      status_code="$(
        curl -s -o /dev/null -w '%{http_code}' \
          -H "Authorization: Bearer ${token}" \
          "http://127.0.0.1:${port}/v1/status" || true
      )"
      if [[ "${status_code}" == "200" ]]; then
        ready=1
        break
      fi
    fi
  fi
  sleep 0.25
done

if [[ "${ready}" -ne 1 ]]; then
  echo "desktop control API did not become ready" >&2
  exit 1
fi

port="$(jq -r '.port' "${RUNTIME_FILE}")"
token="$(jq -r '.token' "${RUNTIME_FILE}")"
base_url="http://127.0.0.1:${port}"

tmpfile="$(mktemp /tmp/gx-phase3-smoke-XXXXXX)"
printf 'digraph G {\n  a -> b\n}\n' > "${tmpfile}"
canonical_path="$(cd "$(dirname "${tmpfile}")" && pwd -P)/$(basename "${tmpfile}")"

echo "watching file: ${canonical_path}"
watch_json="$("${GX_BIN}" watch "${canonical_path}" --json)"
watch_revision="$(jq -r '.revision' <<<"${watch_json}")"
assert_eq "1" "${watch_revision}" "watch revision"

get_initial="$("${GX_BIN}" get --file "${canonical_path}" --json)"
initial_revision="$(jq -r '.revision' <<<"${get_initial}")"
assert_eq "1" "${initial_revision}" "initial revision"

echo "simulating UI write via /v1/document (source=ui)"
ui_text=$'digraph G {\n  a -> c\n}\n'
ui_payload="$(jq -n \
  --arg path "${canonical_path}" \
  --arg text "${ui_text}" \
  --argjson baseRevision "${initial_revision}" \
  '{path: $path, text: $text, baseRevision: $baseRevision, source: "ui"}'
)"

ui_response_file="$(mktemp /tmp/gx-phase3-ui-response-XXXXXX.json)"
ui_status="$(
  curl -sS -o "${ui_response_file}" -w '%{http_code}' \
    -X PUT "${base_url}/v1/document" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    --data "${ui_payload}"
)"
assert_eq "200" "${ui_status}" "ui write status"
ui_revision="$(jq -r '.document.revision' "${ui_response_file}")"
assert_eq "2" "${ui_revision}" "ui write revision"
rm -f "${ui_response_file}"

assert_file_content_eq "${ui_text}" "${canonical_path}" "ui write file content"

echo "writing through gx set"
cli_text=$'digraph G {\n  c -> d\n}\n'
set_json="$("${GX_BIN}" set --file "${canonical_path}" --text "${cli_text}" --json)"
cli_revision="$(jq -r '.revision' <<<"${set_json}")"
assert_eq "3" "${cli_revision}" "cli write revision"
assert_file_content_eq "${cli_text}" "${canonical_path}" "cli write file content"

echo "validating stale revision conflict"
stale_text=$'digraph G {\n  stale -> write\n}\n'
stale_payload="$(jq -n \
  --arg path "${canonical_path}" \
  --arg text "${stale_text}" \
  '{path: $path, text: $text, baseRevision: 2, source: "ui"}'
)"

stale_response_file="$(mktemp /tmp/gx-phase3-stale-response-XXXXXX.json)"
stale_status="$(
  curl -sS -o "${stale_response_file}" -w '%{http_code}' \
    -X PUT "${base_url}/v1/document" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    --data "${stale_payload}"
)"
assert_eq "409" "${stale_status}" "stale write status"
stale_code="$(jq -r '.code' "${stale_response_file}")"
assert_eq "DOCUMENT_CONFLICT" "${stale_code}" "stale write code"
current_revision="$(jq -r '.currentRevision' "${stale_response_file}")"
assert_eq "3" "${current_revision}" "stale current revision"
rm -f "${stale_response_file}"

assert_file_content_eq "${cli_text}" "${canonical_path}" "stale write did not overwrite"

echo "cleaning up watch"
"${GX_BIN}" unwatch "${canonical_path}" --json >/dev/null

echo "phase3 smoke passed"
