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
require_cmd python3
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

echo "waiting for the desktop control channel..."
control_wait_ready 120 || exit 1

tmpfile="$(mktemp /tmp/gx-release-smoke-XXXXXX.dot)"
printf 'digraph G {\n  a -> b\n}\n' > "${tmpfile}"

# gx canonicalized client-side before sending; the gates must do the same, or
# they stop exercising the path the desktop actually receives.
tmpfile_canonical="$(cd "$(dirname "${tmpfile}")" && pwd -P)/$(basename "${tmpfile}")"

status_json="$(api_status)"
assert_eq "ok" "$(api_last_status)" "release status outcome"
assert_eq "true" "$(jq -r '.result.running' <<<"${status_json}")" "release status running"
# The runtime file must carry no credential (D4). A gate is the right place for
# this: it is the file every client reads, and a regression here would be
# invisible until something leaked it.
assert_eq "null" "$(jq -r '.token // "null"' "${RUNTIME_FILE}")" "runtime file has no token"
assert_eq "null" "$(jq -r '.port // "null"' "${RUNTIME_FILE}")" "runtime file has no port"

watch_json="$(api_watch "${tmpfile_canonical}")"
assert_eq "ok" "$(api_last_status)" "release watch outcome"
assert_eq "1" "$(jq -r '.result.revision' <<<"${watch_json}")" "release watch revision"

get_json="$(api_get "${tmpfile_canonical}")"
assert_eq "ok" "$(api_last_status)" "release get outcome"
assert_eq "1" "$(jq -r '.result.document.revision' <<<"${get_json}")" "release get revision"

set_json="$(api_set "${tmpfile_canonical}" $'digraph G {\n  b -> c\n}\n' cli)"
assert_eq "ok" "$(api_last_status)" "release set outcome"
assert_eq "2" "$(jq -r '.result.document.revision' <<<"${set_json}")" "release set revision"

stale_json="$(api_put "${tmpfile_canonical}" $'digraph G {\n  stale -> write\n}\n' 1 cli)"
assert_eq "error" "$(api_last_status)" "release stale write outcome"
assert_eq "DOCUMENT_CONFLICT" "$(jq -r '.error.code' <<<"${stale_json}")" "release stale write code"

unwatch_json="$(api_unwatch "${tmpfile_canonical}")"
assert_eq "true" "$(jq -r '.result.removed' <<<"${unwatch_json}")" "release unwatch removed"

# A path with a space, kept although the bug it guards is now structurally
# impossible. v1 carried this path in a URL and "decoded" it by replacing '%2F'
# with '/' and nothing else, so a space (%20) arrived undecoded, missed the
# watch registry, and `get` failed with exit 4 while `watch` had succeeded --
# the same defect that blocked Windows, where every separator of a canonical
# path (\ : ?) needs decoding. There are no URLs any more (D4), so the encoder
# cannot regress because there is none. It stays because the END-TO-END property
# is what was ever wanted: an awkward path survives from the caller to the
# registry and back.
spacedir="$(dirname "${tmpfile}")/gx smoke dir"
mkdir -p "${spacedir}"
spacedfile="${spacedir}/with space.dot"
printf 'digraph G {\n  a -> b\n}\n' > "${spacedfile}"
# Both the gate and the desktop canonicalize, and on macOS /tmp is a symlink to
# /private/tmp -- so the path that comes back is the resolved one.
spacedfile_canonical="$(cd "${spacedir}" && pwd -P)/with space.dot"

spaced_watch_json="$(api_watch "${spacedfile_canonical}")"
assert_eq "ok" "$(api_last_status)" "spaced-path watch outcome"
assert_eq "1" "$(jq -r '.result.revision' <<<"${spaced_watch_json}")" "spaced-path watch revision"

spaced_get_json="$(api_get "${spacedfile_canonical}")"
assert_eq "ok" "$(api_last_status)" "spaced-path get outcome"
assert_eq "1" "$(jq -r '.result.document.revision' <<<"${spaced_get_json}")" "spaced-path get revision"
assert_eq "${spacedfile_canonical}" "$(jq -r '.result.document.path' <<<"${spaced_get_json}")" "spaced-path get path"

spaced_set_json="$(api_set "${spacedfile_canonical}" $'digraph G {\n  b -> c\n}\n' cli)"
assert_eq "2" "$(jq -r '.result.document.revision' <<<"${spaced_set_json}")" "spaced-path set revision"

api_unwatch "${spacedfile_canonical}" >/dev/null
rm -rf "${spacedir}"

echo "release smoke passed"
