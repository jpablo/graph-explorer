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
# What it pins:
#
#   1. `show` with a library target succeeds only after the page acknowledges.
#      Before the handshake, `show` returned as soon as it had dispatched an
#      event — so `gx open` reported success for opens the user never saw.
#   2. A record that does not exist is REFUSED, not reported as shown. The page
#      cannot route to it, says so, and the refusal reaches the caller instead
#      of the caller waiting out the timeout.
#   3. An open issued against a still-starting desktop is not lost (§4.1). The
#      socket answers from process start, before the webview exists; a request
#      in that window used to be dispatched into a page with no listener.
#   0. A loose file opens on a COLD desktop — one that has displayed nothing
#      yet. Checked FIRST, because opening anything else installs the document
#      listener and hides the failure.
#   4. A LOOSE FILE open is acknowledged too, and the acknowledgment names the
#      document session that displayed it (§15.6, Phase 2 item 7). A file
#      `show` used to keep only the NO_WINDOW check, so `gx open <path>`
#      reported success for a file no viewer had. The second open of the same
#      path is checked as well: registration is idempotent, display is not
#      (§4.2).
#
# Local only, like its siblings: it needs a release desktop binary and a real
# window, neither of which CI's `cargo test --locked` job provides.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/release/graph-explorer-desktop"

# A SANDBOX library, not the user's. GX_HOME moves the library, the socket and
# the audit log together, so this gate can seed records and start a desktop
# without touching a real one — and without killing a desktop the user is using,
# since the socket it advertises lives under this root too.
#
# Absolute by construction: a relative GX_HOME is refused by both halves,
# precisely because the two processes need not share a working directory.
GX_HOME="$(mktemp -d "${TMPDIR:-/tmp}/gx-open-handshake-XXXXXX")"
export GX_HOME
RUNTIME_FILE="${GX_HOME}/runtime/control.json"
LIBRARY_DIR="${GX_HOME}/library/diagrams"

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
record_file=""

cleanup() {
  if [[ -n "${desktop_pid}" ]]; then
    kill "${desktop_pid}" >/dev/null 2>&1 || true
    wait "${desktop_pid}" >/dev/null 2>&1 || true
  fi
  # The whole sandbox, records and socket and audit log together.
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


# A library record `gx import` would have written. Created directly rather than
# by shelling out to gx: this gate is about the open path, and depending on the
# CLI would make a gx bug look like a handshake bug.
seed_record() {
  local id="$1"
  mkdir -p "${LIBRARY_DIR}"
  record_file="${LIBRARY_DIR}/${id}.json"
  # The shape upickle actually reads, not a plausible-looking one. `id` is a
  # single-field case class and serializes as {"value": ...} rather than a bare
  # string, and metadata carries hiddenElements/tags/autoDetectFormat. A record
  # that does not parse is SKIPPED rather than fatal — by design — so getting
  # this wrong presents as "no diagram in this library", which is indeed what it
  # presented as.
  jq -n --arg id "${id}" '{
    id: { value: $id },
    name: "handshake smoke",
    folder: { segments: [] },
    format: "DOT",
    text: "digraph G {\n  a -> b\n}\n",
    binding: null,
    metadata: { hiddenElements: [], tags: [], autoDetectFormat: true },
    createdAt: 1,
    updatedAt: 1
  }' > "${record_file}"
  echo "seeded library record ${id}"
}

# No pkill: the desktop started below advertises its socket under this
# sandbox's runtime directory, so it cannot collide with one the user is
# running — and killing theirs to run a test would be a poor trade.
RECORD_ID="handshake-smoke"
seed_record "${RECORD_ID}"

echo "starting release desktop runtime..."
"${DESKTOP_BIN}" >/tmp/graph-explorer-open-handshake-smoke.log 2>&1 &
desktop_pid=$!

# ---------------------------------------------------------------- 3. cold open
#
# Issued as early as the socket will take it, which is deliberately BEFORE the
# webview is up. The handshake is what makes this wait rather than vanish, so
# this call standing in for "a shell alias that launches the desktop and opens a
# diagram" is the whole point of §4.1.
echo "waiting for the control socket (not the window)..."
if ! control_wait_ready 120; then
  fail "desktop control API did not become ready"
fi

# ------------------------------------- 0. a FILE opens on a cold desktop
#
# BEFORE any library open, on purpose. The document listener used to be
# installed by the first diagram view that mounted, so a desktop sitting on Home
# had none: the document event carrying the file's text was dropped, and the
# open request that followed answered DOCUMENT_NOT_FOUND. Opening a record
# first installed the listener and hid it.
#
# So the order of these two checks is the check. Do not move this below.
cold_file="${GX_HOME}/cold-open.dot"
printf 'digraph G { cold -> start }\n' > "${cold_file}"

echo "opening a loose file on a desktop that has shown nothing yet..."
body="$(api_show_file "${cold_file}")"
if [[ "$(api_last_status)" != "ok" ]]; then
  echo "${body}" >&2
  fail "a cold file open was refused (code: $(api_last_error_code))"
fi
[[ "$(jq -r '.result.view.kind // ""' <<<"${body}")" == "file" ]] \
  || fail "a cold file open was not acknowledged as a file view"
echo "cold file open acknowledged"

# The acknowledgment is a readiness barrier, not a dispatch receipt. A session
# command issued on the next line must see the new viewer every time.
echo "checking immediate session readiness..."
for attempt in 1 2 3; do
  body="$(api_show_file "${cold_file}")"
  [[ "$(api_last_status)" == "ok" ]] || fail "open ${attempt} failed before readiness check"
  body="$(api_reset_view)"
  [[ "$(api_last_status)" == "ok" ]] || {
    echo "${body}" >&2
    fail "reset-view failed immediately after successful open ${attempt} (code: $(api_last_error_code))"
  }
done
echo "three immediate reset-view calls succeeded"

echo "opening a library record against a possibly-starting desktop..."
started_at="$(python3 -c 'import time; print(int(time.time()*1000))')"
body="$(api_show_library "${RECORD_ID}")"
elapsed=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - started_at ))

if [[ "$(api_last_status)" != "ok" ]]; then
  echo "${body}" >&2
  fail "a cold-start open was not served (code: $(api_last_error_code))"
fi

kind="$(jq -r '.result.kind' <<<"${body}")"
[[ "${kind}" == "library" ]] || fail "expected a library result, got '${kind}'"
returned_id="$(jq -r '.result.diagramId' <<<"${body}")"
[[ "${returned_id}" == "${RECORD_ID}" ]] || fail "expected ${RECORD_ID}, got '${returned_id}'"
echo "cold-start open acknowledged in ${elapsed}ms"

# ------------------------------------------------------- 1. the page really answers
#
# The window is up by now, so this second open exercises the steady-state path.
# It has to be acknowledged too: registration being idempotent must not make
# display idempotent (§4.2).
echo "opening the same record again, with the window already up..."
body="$(api_show_library "${RECORD_ID}")"
[[ "$(api_last_status)" == "ok" ]] || {
  echo "${body}" >&2
  fail "a warm open was refused (code: $(api_last_error_code))"
}
echo "warm open acknowledged"

# -------------------------------------------------- 2. an unroutable record is refused
#
# The page cannot route to a record that is not there. It must SAY so: the
# alternative is the caller waiting out the full open budget for an answer that
# was known immediately.
echo "opening a record that does not exist..."
started_at="$(python3 -c 'import time; print(int(time.time()*1000))')"
body="$(api_show_library "no-such-record-at-all")"
elapsed=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - started_at ))

if [[ "$(api_last_status)" == "ok" ]]; then
  echo "${body}" >&2
  fail "opening a nonexistent record reported success"
fi

code="$(api_last_error_code)"
case "${code}" in
  VIEW_REJECTED|DIAGRAM_NOT_FOUND) echo "refused as ${code} in ${elapsed}ms" ;;
  OPEN_TIMEOUT) fail "the page never answered; a known-immediately refusal cost the full budget" ;;
  *) fail "unexpected refusal code '${code}'" ;;
esac

# A refusal the page computed, not one the shell waited out. Ten seconds is far
# under the 45s open budget and far over any plausible round trip.
if (( elapsed > 10000 )); then
  fail "the refusal took ${elapsed}ms — that is a timeout wearing a refusal's code"
fi

# ------------------------------------ 4. a loose file is acknowledged, by session
#
# The defect Phase 2 item 7 repairs. A loose file had no route, so the page
# could not answer "displayed" for one — and a file `show` therefore did not
# ask. It reported success as soon as it had a window.
loose_file="${GX_HOME}/loose-smoke.dot"
printf 'digraph G { smoke -> test }\n' > "${loose_file}"

echo "opening a loose file..."
started_at="$(python3 -c 'import time; print(int(time.time()*1000))')"
body="$(api_show_file "${loose_file}")"
elapsed=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - started_at ))

if [[ "$(api_last_status)" != "ok" ]]; then
  echo "${body}" >&2
  fail "a loose file open was not served (code: $(api_last_error_code))"
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
echo "loose file acknowledged as ${session_id} in ${elapsed}ms"

# §4.2: the watch already exists now. Display is a separate operation and must
# complete the handshake again — and must land on the SAME session, or one file
# would accumulate routes.
echo "opening the same loose file again..."
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
