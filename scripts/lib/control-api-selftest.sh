#!/usr/bin/env bash
#
# Self-test for control-api.sh, with the transport stubbed out.
#
# It exists because of a bug that cost two full CI cycles to find: the helper
# returned the HTTP status in a global variable, and every call site captures the
# body with `$(api_watch ...)` — a command substitution, which is a subshell. The
# assignment died with the child and every assertion read an empty string. The
# desktop was fine, the API was fine, the request succeeded; only the return
# channel was broken, and nothing could show that without a built desktop.
#
# These checks need no desktop, no network and no runtime file. They run in about
# a second, and they cover the parts of the helper that are easy to get wrong and
# invisible until something expensive fails.
#
# Usage: scripts/lib/control-api-selftest.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

failures=0
check() {
  local label="$1" expected="$2" actual="$3"
  if [[ "${expected}" == "${actual}" ]]; then
    echo "  ok    ${label}"
  else
    echo "  FAIL  ${label}: expected [${expected}], got [${actual}]" >&2
    failures=$((failures + 1))
  fi
}

echo "=== control-api.sh self-test ==="

# --- the URL encoder ---------------------------------------------------------
# Every spelling here is one v1 got wrong. The desktop's decoder is the exact
# inverse of `@uri`, so these are the contract.
check "plain path"          "%2Ftmp%2Fa.dot"                  "$(_api_uri_escape '/tmp/a.dot')"
check "space"               "%2Ftmp%2Fwith%20space.dot"       "$(_api_uri_escape '/tmp/with space.dot')"
check "non-ASCII as UTF-8"  "%2Fcaf%C3%A9.dot"                "$(_api_uri_escape '/café.dot')"
check "windows separators"  "C%3A%5Ca%20b.dot"                "$(_api_uri_escape 'C:\a b.dot')"
check "verbatim prefix"     "%5C%5C%3F%5CC%3A%5Cx.dot"        "$(_api_uri_escape '\\?\C:\x.dot')"
check "reserved chars"      "%2Fa%26b%3Dc%3Fd.dot"            "$(_api_uri_escape '/a&b=c?d.dot')"

# --- the status channel ------------------------------------------------------
# The bug this file exists for. Stub the transport so the only thing under test
# is whether the status survives the subshell the call sites impose.
control_load() { CONTROL_BASE="http://stub"; CONTROL_TOKEN="t"; return 0; }
_api_call() { printf '%s' "${STUB_CODE:-200}" > "${API_STATUS_FILE}"; echo "${STUB_BODY:-{\}}"; }

STUB_CODE=200 STUB_BODY='{"ok":true}'
body="$(api_watch /tmp/a.dot)"
check "body reaches the caller"            '{"ok":true}' "${body}"
check "status survives \$( ) capture"      "200"         "$(api_last_status)"

STUB_CODE=409
_="$(api_put /tmp/a.dot text 1 cli)"
check "a 4xx is reported, not swallowed"   "409"         "$(api_last_status)"

# A deliberate 4xx must not kill a caller running under `set -e`.
STUB_CODE=400
if _="$(api_watch /tmp/nope.dot)"; then :; else
  echo "  FAIL  a 4xx aborted the caller" >&2
  failures=$((failures + 1))
fi
check "a 4xx does not abort the caller"    "400"         "$(api_last_status)"

# --- api_set's two-step ------------------------------------------------------
# It GETs the revision then PUTs against it; the status the caller sees must be
# the PUT's, not the GET's.
STUB_CODE=200 STUB_BODY='{"ok":true,"document":{"revision":7}}'
_="$(api_set /tmp/a.dot newtext cli)"
check "api_set reports the write's status" "200"         "$(api_last_status)"

echo
if [[ "${failures}" -eq 0 ]]; then
  echo "control-api self-test: ALL PASS"
else
  echo "control-api self-test: ${failures} FAILED" >&2
  exit 1
fi
