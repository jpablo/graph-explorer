#!/usr/bin/env bash
#
# Self-test for control-api.sh and control-client.py, with no desktop.
#
# It exists because of a bug that cost two full CI cycles to find: the helper
# returned the call's outcome in a global variable, and every call site captures
# the response with `$(api_watch ...)` — a command substitution, which is a
# subshell. The assignment died with the child and every assertion read an empty
# string. The desktop was fine, the API was fine, the request succeeded; only the
# return channel was broken, and nothing could show that without a built desktop.
#
# P5 changed what the other half checks. The percent-encoding cases are gone
# because URLs are: a path is a JSON string now, so `_api_uri_escape` and the
# MSYS workaround it needed no longer exist. In their place this drives the real
# python client against a real socket served by a stub — which covers the part
# that CAN still go wrong, and the part no other gate reaches without a build:
# framing.
#
# These checks need no desktop and no built binaries. They run in about a second.
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

echo "=== control-api self-test ==="

# --- the framing, against a real socket --------------------------------------
# A stub server that echoes the request back inside a success frame. Real
# AF_UNIX, real newline framing, no desktop: this is what makes the awkward
# paths assertable without a 20-minute build.
stub_dir="$(mktemp -d "${TMPDIR:-/tmp}/gx-selftest-XXXXXX")"
stub_sock="${stub_dir}/control.sock"

python3 - "${stub_sock}" <<'PY' &
import json, socket, sys, os
path = sys.argv[1]
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
os.chmod(path, 0o600)
server.listen(8)
while True:
    conn, _ = server.accept()
    with conn:
        buffer = bytearray()
        while b"\n" not in buffer:
            chunk = conn.recv(65536)
            if not chunk:
                break
            buffer.extend(chunk)
        line, _, _ = bytes(buffer).partition(b"\n")
        if not line:
            continue
        request = json.loads(line.decode("utf-8"))
        if request.get("method") == "boom":
            reply = {"id": request.get("id"), "ok": False,
                     "error": {"code": "WATCH_FAILED", "message": "nope"}}
        else:
            reply = {"id": request.get("id"), "ok": True, "result": request.get("params")}
        conn.sendall((json.dumps(reply) + "\n").encode("utf-8"))
PY
stub_pid=$!

cleanup() {
  kill "${stub_pid}" >/dev/null 2>&1 || true
  rm -rf "${stub_dir}"
}
trap cleanup EXIT

for _ in $(seq 1 40); do
  [[ -S "${stub_sock}" ]] && break
  sleep 0.05
done
[[ -S "${stub_sock}" ]] || { echo "stub server never bound" >&2; exit 1; }

# Point the helper at the stub by writing the runtime file it reads. That is
# also a check in itself: the socket path is DISCOVERED from that file, never
# reconstructed, so the two sides cannot disagree about it.
CONTROL_RUNTIME_FILE="${stub_dir}/control.json"
jq -n --arg socket "${stub_sock}" '{pid: 1, socket: $socket, version: "test"}' \
  > "${CONTROL_RUNTIME_FILE}"

# Every spelling here is one v1 got wrong, and each one now has to survive JSON
# encoding, a socket write, a socket read, and JSON decoding — byte for byte.
roundtrip() {
  api_get "$1" | jq -r '.result.path'
}
check "plain path"          "/tmp/a.dot"            "$(roundtrip '/tmp/a.dot')"
check "space"               "/tmp/with space.dot"   "$(roundtrip '/tmp/with space.dot')"
check "non-ASCII as UTF-8"  "/café.dot"             "$(roundtrip '/café.dot')"
check "windows separators"  'C:\a b.dot'            "$(roundtrip 'C:\a b.dot')"
check "verbatim prefix"     '\\?\C:\x.dot'          "$(roundtrip '\\?\C:\x.dot')"
check "reserved chars"      "/a&b=c?d.dot"          "$(roundtrip '/a&b=c?d.dot')"
# A quote and a backslash are what naive string concatenation breaks on, and a
# NEWLINE would split one frame into two — the failure that would desynchronize
# the whole stream rather than just corrupting one value.
check "quotes/backslashes"  '/a"b\c.dot'            "$(roundtrip '/a"b\c.dot')"
check "an embedded newline" "$(printf '/a\nb.dot')" "$(roundtrip "$(printf '/a\nb.dot')")"

check "a live socket is ready"  "0"  "$(control_ready && echo 0 || echo 1)"

# --- the outcome channel -----------------------------------------------------
# The bug this file exists for: does the outcome survive the `$( )` capture the
# call sites impose?
body="$(api_watch /tmp/a.dot)"
check "body reaches the caller"        "/tmp/a.dot"  "$(jq -r '.result.path' <<<"${body}")"
check "outcome survives \$( ) capture" "ok"          "$(api_last_status)"

# An error FRAME is the desktop answering, and must not kill a caller under
# `set -e`.
if _="$(_api_call boom '{}')"; then :; else
  echo "  FAIL  an error frame aborted the caller" >&2
  failures=$((failures + 1))
fi
check "an error frame is reported"     "error"          "$(api_last_status)"
check "the error code reaches the gate" "WATCH_FAILED"  "$(api_last_error_code)"

# --- api_set's two-step ------------------------------------------------------
# It reads the revision then writes against it; the outcome the caller sees must
# be the write's, not the read's.
_="$(api_set /tmp/a.dot newtext cli)"
check "api_set reports the write's outcome" "ok" "$(api_last_status)"

# --- no desktop --------------------------------------------------------------
# A socket file outlives the process that made it. The gate must tell "nothing
# there" apart from "it said no" — the distinction the whole liveness story
# rests on.
kill "${stub_pid}" >/dev/null 2>&1 || true
wait "${stub_pid}" 2>/dev/null || true
check "the stale socket file is still there" "0" "$([[ -S "${stub_sock}" ]] && echo 0 || echo 1)"
_="$(api_status || true)"
check "a stale socket reads as unreachable"  "unreachable" "$(api_last_status)"
check "control_ready says no"                "1"           "$(control_ready && echo 0 || echo 1)"

echo
if [[ "${failures}" -eq 0 ]]; then
  echo "control-api self-test: ALL PASS"
else
  echo "control-api self-test: ${failures} FAILED" >&2
  exit 1
fi
