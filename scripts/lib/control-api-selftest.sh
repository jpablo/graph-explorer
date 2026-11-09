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

# CPython does not expose AF_UNIX on Windows.
#
# Windows itself has supported it since 1803 — that is the whole basis of P5's
# one-transport decision, and the desktop and `gx` both speak it there. It is
# CPython specifically that has never surfaced the constant, so this file's
# subject (control-client.py, used by the POSIX gates) cannot run on Windows at
# all.
#
# That makes skipping honest rather than a dodge: on Windows the gates reach the
# socket through .NET's UnixDomainSocketEndPoint in the pwsh runtime smoke, which
# drives the same frames over the same socket with real Windows paths. The one
# thing this file uniquely covers — the helper's own outcome plumbing — is bash
# code that behaves the same everywhere, and its one platform-sensitive bug (the
# `${2:-{\}}` default) is now asserted by content rather than by success.
#
# Loudly, and with a zero exit: a gate that quietly does nothing is worse than
# one that is absent.
if ! python3 -c "import socket; socket.AF_UNIX" >/dev/null 2>&1; then
  echo "  SKIP  python3 has no AF_UNIX on this platform (Windows: CPython, not the OS)."
  echo "        The socket is covered here by the pwsh runtime smoke, which uses .NET."
  echo
  echo "control-api self-test: SKIPPED"
  exit 0
fi

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
        elif request.get("method") == "status":
            # A real desktop's status, because `control_ready` now reads it:
            # the socket is bound before the webview, so answering is no longer
            # the same as being usable. Touch `starting` in the stub dir to make
            # this stub report the state a desktop is in while its window comes
            # up. `echo` keeps the params assertion below possible.
            starting = os.path.exists(os.path.join(os.path.dirname(path), "starting"))
            reply = {"id": request.get("id"), "ok": True, "result": {
                "running": not starting,
                "state": "starting" if starting else "running",
                "echo": request.get("params"),
            }}
        else:
            reply = {"id": request.get("id"), "ok": True, "result": request.get("params")}
        conn.sendall((json.dumps(reply) + "\n").encode("utf-8"))
PY
stub_pid=$!

cleanup() {
  kill "${stub_pid}" >/dev/null 2>&1 || true
  rm -rf "${stub_dir}"
  # The sandbox of the checks of gx_sandbox_create, if a check stopped early.
  gx_sandbox_remove
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

# A call that omits params must still send a valid JSON object.
#
# This is the case CI found and this file did not: `status` is the only caller
# that defaults, the default was written `${2:-{\}}`, and that expansion keeps
# the backslash on bash 3.2 (the macOS runner) while producing `{}` on bash 5
# (a laptop). The old check only asked whether the call SUCCEEDED, which is a
# property the bug happened to break in a way that looked like "no desktop".
# Asserting the frame's content instead makes it fail on any bash.
check "a defaulted params object is valid JSON" "{}" "$(api_status | jq -c '.result.echo')"

check "a live socket is ready"  "0"  "$(control_ready && echo 0 || echo 1)"

# A socket that answers but has no window yet is NOT ready. This is the
# distinction the desktop gained when it started binding before the webview,
# and the one the release smoke found missing: `control_ready` used to accept
# any successful reply, so every caller raced the window and asserted
# `running == true` a line later.
touch "${stub_dir}/starting"
check "a starting desktop is not ready" "1" "$(control_ready && echo 0 || echo 1)"
check "and it says so"                  "starting" "$(api_status | jq -r '.result.state')"
rm -f "${stub_dir}/starting"
check "and it is ready once the window is up" "0" "$(control_ready && echo 0 || echo 1)"

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

# --- the sandbox of a gate ---------------------------------------------------
# Each gate that starts the desktop or gx must run in its own GX_HOME and
# GX_DIAGRAMS_DIR. Else the first start of a new desktop moves the real library
# of the person. These checks run last, because gx_sandbox_create changes
# CONTROL_RUNTIME_FILE, which the checks above point at the stub.
saved_tmpdir="${TMPDIR:-}"
expected_base="${TMPDIR:-/tmp}"
expected_base="${expected_base%/}"

# In the real TMPDIR, with a short name, as a gate makes it. The cleanup above
# removes it if a check stops the script.
gx_sandbox_create st >/dev/null
check "the sandbox exists"                     "0" "$([[ -d "${GX_SANDBOX}" ]] && echo 0 || echo 1)"
check "the sandbox is in TMPDIR"               "${expected_base}" "$(dirname "${GX_SANDBOX}")"
check "GX_HOME is the sandbox"                 "${GX_SANDBOX}" "${GX_HOME}"
check "GX_DIAGRAMS_DIR is in the sandbox"      "${GX_SANDBOX}/diagrams" "${GX_DIAGRAMS_DIR}"
check "the runtime file is in the sandbox"     "${GX_SANDBOX}/runtime/control.json" "${CONTROL_RUNTIME_FILE}"
# A child process (the desktop, gx) must see the variables.
check "a child sees GX_HOME"                   "${GX_SANDBOX}" "$(bash -c 'printf %s "${GX_HOME}"')"
check "a child sees GX_DIAGRAMS_DIR"           "${GX_SANDBOX}/diagrams" "$(bash -c 'printf %s "${GX_DIAGRAMS_DIR}"')"
check "the python client follows GX_HOME" "${GX_SANDBOX}/runtime/control.json" \
  "$(env -u CONTROL_RUNTIME_FILE python3 -B -c "import importlib.util as u
s = u.spec_from_file_location('c', '${CONTROL_CLIENT}'); m = u.module_from_spec(s); s.loader.exec_module(m)
print(m.DEFAULT_CONTROL)")"

touch "${GX_SANDBOX}/a-file"
gx_sandbox_remove
check "the sandbox goes, with its files"       "1" "$([[ -e "${GX_SANDBOX}" ]] && echo 0 || echo 1)"

# The removal must never remove a directory that it did not make.
other_dir="${stub_dir}/not-a-sandbox"
mkdir -p "${other_dir}"
( GX_SANDBOX="${other_dir}"; gx_sandbox_remove )
check "a different directory stays"            "0" "$([[ -d "${other_dir}" ]] && echo 0 || echo 1)"

# A socket path over 100 bytes makes the desktop refuse to start. The helper
# says so at once, and leaves nothing behind.
long_dir="${stub_dir}/$(printf 'x%.0s' $(seq 1 90))"
mkdir -p "${long_dir}"
export TMPDIR="${long_dir}"
if gx_sandbox_create selftest >/dev/null 2>&1; then long_outcome=0; else long_outcome=1; fi
check "a socket path over 100 bytes is refused" "1" "${long_outcome}"
check "and the refused sandbox does not stay"   "" "$(ls -A "${long_dir}")"

if [[ -n "${saved_tmpdir}" ]]; then export TMPDIR="${saved_tmpdir}"; else unset TMPDIR; fi

echo
if [[ "${failures}" -eq 0 ]]; then
  echo "control-api self-test: ALL PASS"
else
  echo "control-api self-test: ${failures} FAILED" >&2
  exit 1
fi
