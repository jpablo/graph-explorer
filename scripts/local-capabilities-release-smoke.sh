#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
GX_BIN="${ROOT_DIR}/gx/target/release/gx"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/release/graph-explorer-desktop"

desktop_pid=""
tmpfile=""

cleanup() {
  if [[ -n "${tmpfile}" && -f "${tmpfile}" ]]; then
    rm -f "${tmpfile}"
  fi
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

require_cmd jq
require_cmd mktemp

if [[ ! -x "${GX_BIN}" ]]; then
  echo "missing release gx binary at ${GX_BIN}" >&2
  exit 1
fi

if [[ ! -x "${DESKTOP_BIN}" ]]; then
  echo "missing release desktop binary at ${DESKTOP_BIN}" >&2
  exit 1
fi

pkill -f graph-explorer-desktop >/dev/null 2>&1 || true
rm -f "${RUNTIME_FILE}"

echo "starting release desktop runtime..."
"${DESKTOP_BIN}" >/tmp/graph-explorer-desktop-release-smoke.log 2>&1 &
desktop_pid=$!

echo "waiting for desktop control API..."
ready=0
for _ in $(seq 1 120); do
  status_json="$("${GX_BIN}" status --json || true)"
  running="$(jq -r '.running // false' <<<"${status_json}")"
  if [[ "${running}" == "true" && -f "${RUNTIME_FILE}" ]]; then
    ready=1
    break
  fi
  sleep 0.25
done

if [[ "${ready}" -ne 1 ]]; then
  echo "desktop control API did not become ready" >&2
  exit 1
fi

tmpfile="$(mktemp /tmp/gx-release-smoke-XXXXXX.dot)"
printf 'digraph G {\n  a -> b\n}\n' > "${tmpfile}"

status_json="$("${GX_BIN}" status --json)"
status_running="$(jq -r '.running' <<<"${status_json}")"
assert_eq "true" "${status_running}" "release status running"

watch_json="$("${GX_BIN}" watch "${tmpfile}" --json)"
watch_revision="$(jq -r '.revision' <<<"${watch_json}")"
assert_eq "1" "${watch_revision}" "release watch revision"

get_json="$("${GX_BIN}" get --file "${tmpfile}" --json)"
get_revision="$(jq -r '.revision' <<<"${get_json}")"
assert_eq "1" "${get_revision}" "release get revision"

set_json="$("${GX_BIN}" set --file "${tmpfile}" --text $'digraph G {\n  b -> c\n}\n' --json)"
set_revision="$(jq -r '.revision' <<<"${set_json}")"
assert_eq "2" "${set_revision}" "release set revision"

set +e
stale_json="$("${GX_BIN}" set --file "${tmpfile}" --text $'digraph G {\n  stale -> write\n}\n' --base-revision 1 --json)"
stale_exit=$?
set -e
assert_eq "5" "${stale_exit}" "release stale write exit code"
stale_code="$(jq -r '.code' <<<"${stale_json}")"
assert_eq "DOCUMENT_CONFLICT" "${stale_code}" "release stale write code"

unwatch_json="$("${GX_BIN}" unwatch "${tmpfile}" --json)"
removed="$(jq -r '.removed' <<<"${unwatch_json}")"
assert_eq "true" "${removed}" "release unwatch removed"

echo "release smoke passed"
