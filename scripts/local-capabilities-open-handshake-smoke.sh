#!/usr/bin/env bash

# Integration test for the OPEN ACKNOWLEDGMENT HANDSHAKE (§4 of
# docs/desktop-open-targets-and-persistence.md).
#
# This is the one part of the open path that unit tests cannot reach. `cargo
# test` has no webview, so it can assert that a timeout produces OPEN_TIMEOUT
# and that an unroutable payload is refused, but not the thing that matters:
# that the shell dispatches, a real page mounts the right view, calls back
# through `complete_open`, and only THEN does the socket answer the caller.
#
# What it pins. A diagram is a file (ADR 0001), so each open is a FILE open.
# The `show` request has no `library` target kind any more.
#
#   0. A file opens on a COLD desktop — one that has displayed nothing yet.
#      Checked FIRST, because an earlier open installs the document listener
#      and hides the failure.
#   1. An open against a desktop that can still be starting is served (§4.1),
#      and it succeeds only after the page acknowledges. The socket answers
#      from process start, before the webview exists. Before the handshake,
#      `show` returned as soon as it had dispatched an event, so `gx open`
#      reported success for opens the user never saw.
#   2. A file that does not exist is REFUSED quickly. The refusal reaches the
#      caller, and the caller does not wait for the timeout.
#   3. A second open of the same file, with the window up, is acknowledged
#      too: registration is idempotent, display is not (§4.2).
#   4. The acknowledgment names the document session that displayed the file
#      (§15.6, Phase 2 item 7), and a second open lands on the same session.
#
# Local only, like its siblings: it needs a release desktop binary and a real
# window, neither of which CI's `cargo test --locked` job provides.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/release/graph-explorer-desktop"

# A SANDBOX library, not the user's. GX_HOME moves the library, the socket and
# the audit log together, so this gate can start a desktop without touching a
# real one — and without killing a desktop the user is using, since the socket
# it advertises lives under this root too.
#
# Absolute by construction: a relative GX_HOME is refused by both halves,
# precisely because the two processes need not share a working directory.
GX_HOME="$(mktemp -d "${TMPDIR:-/tmp}/gx-open-handshake-XXXXXX")"
export GX_HOME
RUNTIME_FILE="${GX_HOME}/runtime/control.json"

# GX_HOME does not move the folder for new diagrams. The desktop migration
# writes files into that folder at the first start, so it must be in the
# sandbox too, and not in the real ~/Documents.
GX_DIAGRAMS_DIR="${GX_HOME}/diagrams"
export GX_DIAGRAMS_DIR

# The control helpers read this to find the socket.
export CONTROL_RUNTIME_FILE="${RUNTIME_FILE}"

# Longer than the shell's 45s open budget. The client's 10s default expires
# BEFORE the desktop's own timeout, so a genuine OPEN_TIMEOUT came back as
# "unreachable" with no error code — the gate could not tell a page that never
# answered from a socket that was never there.
export API_TIMEOUT=60

# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

desktop_pid=""

cleanup() {
  if [[ -n "${desktop_pid}" ]]; then
    kill "${desktop_pid}" >/dev/null 2>&1 || true
    wait "${desktop_pid}" >/dev/null 2>&1 || true
  fi
  # The whole sandbox: the library, the diagrams, the socket and the audit log.
  if [[ -n "${GX_HOME}" && "${GX_HOME}" == */gx-open-handshake-* ]]; then
    rm -rf "${GX_HOME}"
  fi
}
trap cleanup EXIT

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required command: $1" >&2
    exit 1
  }
}

# Two things about this build are easy to get wrong, and BOTH present as a blank
# window rather than as a build problem:
#
#   1. The frontend must be built FIRST. A release build embeds it, so a dist/
#      rebuilt afterwards is simply ignored.
#   2. `cargo build --release` alone is NOT enough. The embedded assets are
#      served over the custom protocol, which is a cargo FEATURE that
#      `cargo tauri build` sets implicitly and a raw cargo build does not. A
#      binary without it opens a window and loads nothing: no page, no JS, no
#      IPC — and therefore no viewer_ready, which this gate then reports as an
#      OPEN_TIMEOUT. That reads like a broken handshake and is not one.
print_build_instructions() {
  echo "build in this order:"
  echo "  sbt 'viewer/fullLinkJS' && npm run build"
  echo "  (cd desktop/src-tauri && cargo build --release --features tauri/custom-protocol)"
  echo
  echo "or, equivalently, with the Tauri CLI (which sets that feature for you):"
  echo "  cargo install tauri-cli --version '^2'   # once"
  echo "  sbt 'viewer/fullLinkJS' && npm run build && (cd desktop/src-tauri && cargo tauri build)"
}

require_cmd jq
require_cmd python3

if [[ ! -x "${DESKTOP_BIN}" ]]; then
  echo "missing release desktop binary at ${DESKTOP_BIN}" >&2
  print_build_instructions >&2
  exit 1
fi

# A RELEASE build EMBEDS the frontend: `generate_context!` bakes `frontendDist`
# into the binary at compile time, so a newer dist/ is simply ignored. Building
# in the wrong order therefore produces a desktop whose Rust half expects
# `viewer_ready` and whose page has never heard of it — which presents as a
# blank window and an OPEN_TIMEOUT, i.e. as a broken handshake rather than as a
# stale build. It cost a debugging cycle once; it should cost nobody another.
stale_against_binary() {
  [[ -e "$1" && "$1" -nt "${DESKTOP_BIN}" ]]
}

if stale_against_binary "${ROOT_DIR}/dist/index.html" \
  || stale_against_binary "${ROOT_DIR}/desktop/src-tauri/src/main.rs"; then
  echo "the release binary is older than what it is supposed to contain:" >&2
  echo "  binary       $(date -r "${DESKTOP_BIN}" '+%Y-%m-%d %H:%M' 2>/dev/null)" >&2
  echo "  dist/        $(date -r "${ROOT_DIR}/dist/index.html" '+%Y-%m-%d %H:%M' 2>/dev/null)" >&2
  echo "  main.rs      $(date -r "${ROOT_DIR}/desktop/src-tauri/src/main.rs" '+%Y-%m-%d %H:%M' 2>/dev/null)" >&2
  echo >&2
  print_build_instructions >&2
  exit 1
fi

fail() {
  echo "FAIL: $*" >&2
  exit 1
}


echo "starting release desktop runtime..."
"${DESKTOP_BIN}" >/tmp/graph-explorer-open-handshake-smoke.log 2>&1 &
desktop_pid=$!

echo "waiting for the control socket..."
if ! control_wait_ready 120; then
  fail "desktop control API did not become ready"
fi

# ------------------------------------- 0. a FILE opens on a cold desktop
#
# BEFORE any other open, on purpose. The document listener used to be
# installed by the first diagram view that mounted, so a desktop on Home had
# none: the document event with the text of the file was dropped, and the open
# request that came next answered DOCUMENT_NOT_FOUND. An earlier open installed
# the listener and hid the failure.
#
# So the order of the checks is the check. Do not move this below.
cold_file="${GX_HOME}/cold-open.dot"
printf 'digraph G { cold -> start }\n' > "${cold_file}"

echo "opening a file on a desktop that has shown nothing yet..."
body="$(api_show_file "${cold_file}")"
if [[ "$(api_last_status)" != "ok" ]]; then
  echo "${body}" >&2
  fail "a cold file open was refused (code: $(api_last_error_code))"
fi
[[ "$(jq -r '.result.view.kind // ""' <<<"${body}")" == "file" ]] \
  || fail "a cold file open was not acknowledged as a file view"
echo "cold file open acknowledged"

# The acknowledgment is a readiness barrier, not a dispatch receipt. A live view
# command on the next line must see the new viewer every time.
#
# A live view command names its diagram by the CANONICAL path (plan §2). On
# macOS, a temporary directory is behind a symlink, so the path that the shell
# gives the page differs from the path that this script wrote.
cold_canonical="$(cd "$(dirname "${cold_file}")" && pwd -P)/$(basename "${cold_file}")"
echo "checking immediate live view readiness..."
for attempt in 1 2 3; do
  body="$(api_show_file "${cold_file}")"
  [[ "$(api_last_status)" == "ok" ]] || fail "open ${attempt} failed before readiness check"
  body="$(api_reset_view "${cold_canonical}")"
  [[ "$(api_last_status)" == "ok" ]] || {
    echo "${body}" >&2
    fail "reset-view failed immediately after successful open ${attempt} (code: $(api_last_error_code))"
  }
done
echo "three immediate reset-view calls succeeded"

# A live view command for a diagram that is not on the screen is refused, and
# the message names the file on the screen.
body="$(api_reset_view "${GX_HOME}/not-on-the-screen.dot")"
[[ "$(api_last_status)" == "error" && "$(api_last_error_code)" == "VIEW_REJECTED" ]] || {
  echo "${body}" >&2
  fail "a reset-view for a different file was not refused with VIEW_REJECTED (code: $(api_last_error_code))"
}
jq -r '.error.message // ""' <<<"${body}" | grep -qF "${cold_canonical}" \
  || fail "the VIEW_REJECTED message does not name the file on the screen: ${body}"
echo "a reset-view for a different file was refused, and the refusal names the file on the screen"

# ------------------------------- 1. an open on a possibly-starting desktop
#
# The cold open above can arrive before the webview is up. The handshake makes
# it wait, and not vanish (§4.1). This open of a second file measures the
# time, and it must be acknowledged as a file view.
started_file="${GX_HOME}/started.dot"
printf 'digraph G { started -> desktop }\n' > "${started_file}"

echo "opening a file against a possibly-starting desktop..."
started_at="$(python3 -c 'import time; print(int(time.time()*1000))')"
body="$(api_show_file "${started_file}")"
elapsed=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - started_at ))

if [[ "$(api_last_status)" != "ok" ]]; then
  echo "${body}" >&2
  fail "a file open was not served (code: $(api_last_error_code))"
fi
[[ "$(jq -r '.result.view.kind // ""' <<<"${body}")" == "file" ]] \
  || fail "the open was not acknowledged as a file view"
echo "file open acknowledged in ${elapsed}ms"

# ------------------------------------------------------- 3. the page really answers
#
# The window is up now, so this second open uses the steady path. It must be
# acknowledged too: registration is idempotent, and display must not be
# (§4.2).
echo "opening the same file again, with the window already up..."
body="$(api_show_file "${started_file}")"
[[ "$(api_last_status)" == "ok" ]] || {
  echo "${body}" >&2
  fail "a warm open was refused (code: $(api_last_error_code))"
}
echo "warm open acknowledged"

# -------------------------------------------------- 2. a missing file is refused
#
# The shell cannot watch a file that is not there. It must SAY so at once. The
# other answer is a caller that waits for the full open budget.
echo "opening a file that does not exist..."
started_at="$(python3 -c 'import time; print(int(time.time()*1000))')"
body="$(api_show_file "${GX_HOME}/no-such-file-at-all.dot")"
elapsed=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - started_at ))

if [[ "$(api_last_status)" == "ok" ]]; then
  echo "${body}" >&2
  fail "opening a file that does not exist reported success"
fi

code="$(api_last_error_code)"
case "${code}" in
  WATCH_FAILED) echo "refused as ${code} in ${elapsed}ms" ;;
  OPEN_TIMEOUT) fail "the page never answered; a known-immediately refusal cost the full budget" ;;
  *) fail "unexpected refusal code '${code}'" ;;
esac

# A refusal the shell computed, not one it waited out. Ten seconds is far
# under the 45s open budget and far over any plausible round trip.
if (( elapsed > 10000 )); then
  fail "the refusal took ${elapsed}ms — that is a timeout wearing a refusal's code"
fi

# ------------------------------------ 4. a file is acknowledged, by session
#
# The defect Phase 2 item 7 repairs. A file had no route, so the page could
# not answer "displayed" for one, and a file `show` did not ask. It reported
# success as soon as it had a window.
loose_file="${GX_HOME}/loose-smoke.dot"
printf 'digraph G { smoke -> test }\n' > "${loose_file}"

echo "opening a file..."
started_at="$(python3 -c 'import time; print(int(time.time()*1000))')"
body="$(api_show_file "${loose_file}")"
elapsed=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - started_at ))

if [[ "$(api_last_status)" != "ok" ]]; then
  echo "${body}" >&2
  fail "a file open was not served (code: $(api_last_error_code))"
fi

# The acknowledgment has to say WHAT was displayed. Without this the check
# would pass for a shell that answered before any viewer mounted, which is
# exactly the defect.
view_kind="$(jq -r '.result.view.kind // ""' <<<"${body}")"
[[ "${view_kind}" == "file" ]] || {
  echo "${body}" >&2
  fail "the page did not acknowledge a FILE view (got '${view_kind}')"
}

session_id="$(jq -r '.result.view.sessionId // ""' <<<"${body}")"
[[ -n "${session_id}" ]] || fail "the acknowledgment named no document session"

# §13: the session id is opaque. A path in it would end up in the route URL.
case "${session_id}" in
  doc-*) : ;;
  *) fail "expected an opaque session id, got '${session_id}'" ;;
esac
case "${session_id}" in
  */*|*loose-smoke*) fail "the session id leaks the path: '${session_id}'" ;;
esac
echo "file acknowledged as ${session_id} in ${elapsed}ms"

# §4.2: the watch already exists now. Display is a separate operation and must
# complete the handshake again — and must land on the SAME session, or one file
# would accumulate routes.
echo "opening the same file again..."
body="$(api_show_file "${loose_file}")"
[[ "$(api_last_status)" == "ok" ]] || {
  echo "${body}" >&2
  fail "a second open of a watched file was refused (code: $(api_last_error_code))"
}

again="$(jq -r '.result.view.sessionId // ""' <<<"${body}")"
[[ "${again}" == "${session_id}" ]] || {
  fail "a second open minted a new session ('${again}' != '${session_id}')"
}
echo "second open acknowledged, same session"

# A file is watched before its bytes can be delivered to the page. If parsing
# rejects those bytes, the watch created for that failed open must be rolled
# back. Otherwise each typo grows status by one stale path.
bad_file="${GX_HOME}/invalid-open.dot"
printf 'digraph G { a -> }\n' > "${bad_file}"
before="$(api_status)"
before_count="$(jq -r '.result.watches | length' <<<"${before}")"

echo "opening an invalid diagram..."
body="$(api_show_file "${bad_file}")"
[[ "$(api_last_status)" == "error" ]] || fail "an invalid diagram reported success"
[[ "$(api_last_error_code)" == "PARSE_FAILED" ]] || {
  echo "${body}" >&2
  fail "invalid diagram was not reported as PARSE_FAILED"
}

after="$(api_status)"
after_count="$(jq -r '.result.watches | length' <<<"${after}")"
[[ "${after_count}" == "${before_count}" ]] || {
  echo "${after}" >&2
  fail "failed open leaked a watch (${before_count} -> ${after_count})"
}
if jq -e --arg path "${bad_file}" '.result.watches | any(.path == $path)' <<<"${after}" >/dev/null; then
  fail "failed-open path remains in normalized watch status"
fi
echo "parse failure reported and its watch rolled back"

echo
echo "PASS: open acknowledgment handshake"
