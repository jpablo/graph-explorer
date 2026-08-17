#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/release/graph-explorer-desktop"

# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

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
require_cmd curl
require_cmd mktemp

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
control_wait_ready 120 || exit 1

tmpfile="$(mktemp /tmp/gx-release-smoke-XXXXXX.dot)"
printf 'digraph G {\n  a -> b\n}\n' > "${tmpfile}"

# gx canonicalized client-side before sending; the gates must do the same, or
# they stop exercising the path the desktop actually receives.
tmpfile_canonical="$(cd "$(dirname "${tmpfile}")" && pwd -P)/$(basename "${tmpfile}")"

status_json="$(api_status)"
assert_eq "200" "$(api_last_status)" "release status code"
assert_eq "true" "$(jq -r '.running' <<<"${status_json}")" "release status running"

watch_json="$(api_watch "${tmpfile_canonical}")"
assert_eq "200" "$(api_last_status)" "release watch status"
assert_eq "1" "$(jq -r '.watch.revision' <<<"${watch_json}")" "release watch revision"

get_json="$(api_get "${tmpfile_canonical}")"
assert_eq "200" "$(api_last_status)" "release get status"
assert_eq "1" "$(jq -r '.document.revision' <<<"${get_json}")" "release get revision"

set_json="$(api_set "${tmpfile_canonical}" $'digraph G {\n  b -> c\n}\n' cli)"
assert_eq "200" "$(api_last_status)" "release set status"
assert_eq "2" "$(jq -r '.document.revision' <<<"${set_json}")" "release set revision"

stale_json="$(api_put "${tmpfile_canonical}" $'digraph G {\n  stale -> write\n}\n' 1 cli)"
assert_eq "409" "$(api_last_status)" "release stale write status"
assert_eq "DOCUMENT_CONFLICT" "$(jq -r '.code' <<<"${stale_json}")" "release stale write code"

unwatch_json="$(api_unwatch "${tmpfile_canonical}")"
assert_eq "true" "$(jq -r '.removed' <<<"${unwatch_json}")" "release unwatch removed"

# A path that needs more than '/' escaped. The gate sends the path to
# `GET /v1/document` percent-encoded, and the desktop used to "decode" it by
# replacing '%2F' with '/' and nothing else -- so a space (%20) arrived
# undecoded, missed the watch registry, and `get` failed with exit 4 while
# `watch` had succeeded. That is the same defect that blocked Windows, where
# every separator of a canonical path (\ : ?) needs decoding. Keep this case:
# on macOS/Linux it is the only cheap guard against the encoding regressing.
spacedir="$(dirname "${tmpfile}")/gx smoke dir"
mkdir -p "${spacedir}"
spacedfile="${spacedir}/with space.dot"
printf 'digraph G {\n  a -> b\n}\n' > "${spacedfile}"
# Both the gate and the desktop canonicalize, and on macOS /tmp is a symlink to
# /private/tmp -- so the path that comes back is the resolved one.
spacedfile_canonical="$(cd "${spacedir}" && pwd -P)/with space.dot"

spaced_watch_json="$(api_watch "${spacedfile_canonical}")"
assert_eq "200" "$(api_last_status)" "spaced-path watch status"
assert_eq "1" "$(jq -r '.watch.revision' <<<"${spaced_watch_json}")" "spaced-path watch revision"

spaced_get_json="$(api_get "${spacedfile_canonical}")"
assert_eq "200" "$(api_last_status)" "spaced-path get status"
assert_eq "1" "$(jq -r '.document.revision' <<<"${spaced_get_json}")" "spaced-path get revision"
assert_eq "${spacedfile_canonical}" "$(jq -r '.document.path' <<<"${spaced_get_json}")" "spaced-path get path"

spaced_set_json="$(api_set "${spacedfile_canonical}" $'digraph G {\n  b -> c\n}\n' cli)"
assert_eq "2" "$(jq -r '.document.revision' <<<"${spaced_set_json}")" "spaced-path set revision"

api_unwatch "${spacedfile_canonical}" >/dev/null
rm -rf "${spacedir}"

echo "release smoke passed"
