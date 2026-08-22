#!/usr/bin/env bash
#
# The desktop's control channel, driven directly.
#
# The gates used to reach the desktop through the Rust `gx`, which made the CLI
# load-bearing for the DESKTOP's test coverage. They then spoke its loopback
# HTTP API directly. Since P5 (docs/desktop-gx-v2-architecture.md D4) there is
# no HTTP: the channel is a unix socket carrying one JSON object per line, and
# the frames go through `control-client.py` because bash cannot open a unix
# socket on its own.
#
# WHAT THIS FILE USED TO BE ABOUT, and no longer is: percent-encoding. v1
# carried paths in a URL, and a hand-rolled decoder that handled `%2F` and
# nothing else survived five months -- a plain POSIX path worked, a path with a
# space did not, and every canonical Windows path was mangled. The encoder that
# replaced it lived here so there was exactly one of it.
#
# There is now none of it. A path is a JSON string, and json.dumps is not a
# thing this repository implements. The API_URI_ESCAPE helper, its MSYS
# workaround, and the self-test that caught git-bash rewriting POSIX-looking
# paths are all deleted, because the bug they contained cannot occur without a
# URL. The awkward paths they guarded are now asserted in the desktop's own
# `a_path_survives_the_frame_intact` test and in the framing self-test.
#
# Usage:
#   source "${ROOT_DIR}/scripts/lib/control-api.sh"
#   control_wait_ready 80 || exit 1
#   body="$(api_watch "/abs/path.dot")"   # response frame on stdout
#   api_last_status                        # "ok" | "error" | "unreachable"

CONTROL_RUNTIME_FILE="${CONTROL_RUNTIME_FILE:-${HOME}/.graph-explorer/runtime/control.json}"
CONTROL_SOCKET=""
CONTROL_CLIENT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/control-client.py"

# The call's outcome goes through a FILE, not a variable.
#
# Every call site captures the response with `$(api_watch ...)`, and a command
# substitution is a subshell: a variable the function assigns there dies with
# the child, so the parent read an empty string on every assertion. stdout
# already carries the frame, and a second return value cannot come back the same
# way.
#
# Deliberately NOT cleaned up with a trap: this is a sourced library, and every
# caller installs its own `trap cleanup EXIT` to kill the desktop it started. A
# trap here would silently replace theirs, trading a stray temp file for a
# leaked desktop process.
API_STATUS_FILE="$(mktemp "${TMPDIR:-/tmp}/gx-api-status-XXXXXX")"

api_last_status() { cat "${API_STATUS_FILE}" 2>/dev/null || echo ""; }

# Did the last call return an error FRAME (as opposed to failing to connect)?
# The gates assert on both, and they are different things: a denied path is the
# desktop answering, an unreachable socket is the desktop not being there.
api_last_error_code() {
  jq -r '.error.code // empty' < "${API_STATUS_FILE}.body" 2>/dev/null || echo ""
}

# Read the socket path from the runtime file. Returns 1 if it is absent, which
# is the ordinary "no desktop yet" case, not an error.
control_load() {
  # Plain `jq` on purpose: the runtime file is a real FILE argument, and MSYS's
  # path conversion is what turns `/c/Users/...` into something jq.exe can open.
  [[ -f "${CONTROL_RUNTIME_FILE}" ]] || return 1
  CONTROL_SOCKET="$(jq -r '.socket // empty' "${CONTROL_RUNTIME_FILE}" 2>/dev/null || true)"
  [[ -n "${CONTROL_SOCKET}" ]] || return 1
  return 0
}

# Is the desktop actually answering?
#
# The runtime file outlives a crash and so does the socket FILE, so neither
# one's existence proves anything. Connecting does -- which is why this is a
# real call rather than a stat.
control_ready() {
  control_load || return 1
  local body
  body="$(api_status 2>/dev/null)"
  [[ "$(api_last_status)" == "ok" ]] || return 1

  # READY MEANS THE WINDOW IS UP, not merely that the socket answers.
  #
  # The desktop binds its control socket before the webview now, so that it can
  # say "starting" instead of looking absent for the 15-30s WebView2 takes on
  # Windows. A bound socket therefore stopped being proof of a usable desktop:
  # it answers `running: false` while starting.
  #
  # Waiting on the weaker signal made every caller race the webview. The release
  # smoke caught it immediately -- it waited, got an answer, and then asserted
  # `running == true` one line later and failed. The Windows gate never had this
  # bug: its readiness loop has always checked `$status.result.running`, so the
  # two implementations of the same wait disagreed, and only the stricter one
  # was right.
  [[ "$(_jq -r '.result.running' <<<"${body}")" == "true" ]]
}

control_wait_ready() {
  local tries="${1:-80}"
  for _ in $(seq 1 "${tries}"); do
    if control_ready; then return 0; fi
    sleep 0.25
  done
  echo "desktop control channel did not become ready" >&2
  return 1
}

# jq, with MSYS path mangling turned off.
#
# git-bash rewrites arguments that LOOK like POSIX paths into Windows paths
# before handing them to a native Windows binary, and jq.exe is one: `/tmp/a.dot`
# arrives as `C:/Users/RUNNER~1/AppData/Local/Temp/a.dot`. Only arguments that
# are DATA need this; control_load above passes a real file and must not.
_jq() {
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' jq "$@"
}

# $1 method, $2 optional JSON params object.
# Response frame on stdout; outcome via `api_last_status`. Never fails the
# caller's `set -e`, so a script can assert on a rejection deliberately.
_api_call() {
  local method="$1" params="${2:-}"
  # Spelled out rather than `${2:-{\}}`. That expansion means "default to {}" on
  # bash 5 and keeps the backslash on bash 3.2, which is what the macOS runner
  # has — so `status` (the only caller that omits params) sent the literal `{\}`,
  # python's json.loads threw, and the traceback went to the /dev/null below.
  # The gate reported "error" for a live socket and could not tell a dead one
  # from a refusal. Nothing about it was visible locally.
  [[ -n "${params}" ]] || params='{}'

  control_load || {
    printf '%s' "unreachable" > "${API_STATUS_FILE}"
    : > "${API_STATUS_FILE}.body"
    echo "control runtime file unavailable" >&2
    return 1
  }

  local body code
  body="$(python3 "${CONTROL_CLIENT}" "${method}" "${params}" --socket "${CONTROL_SOCKET}" 2>/dev/null)"
  code=$?

  case "${code}" in
    0) printf '%s' "ok" > "${API_STATUS_FILE}" ;;
    1) printf '%s' "error" > "${API_STATUS_FILE}" ;;
    *) printf '%s' "unreachable" > "${API_STATUS_FILE}" ;;
  esac
  printf '%s' "${body}" > "${API_STATUS_FILE}.body"
  printf '%s' "${body}"
}

api_status() { _api_call status; }

# Every path now travels the same way -- as a JSON string in `params` -- so the
# watch/get asymmetry that hid v1's decoding bug (body for one, URL for the
# other) is gone with the URLs.
api_watch() {
  _api_call watch "$(_jq -n --arg path "$1" '{path: $path}')"
}

# `show` carries a TYPED target since the open-targets work: a bare path could
# not say whether it meant a library record or a loose file, and a record with
# no origin has no path to send at all.
api_show_file() {
  _api_call show "$(_jq -n --arg path "$1" '{target: {kind: "file", path: $path}}')"
}

api_show_library() {
  _api_call show "$(_jq -n --arg id "$1" '{target: {kind: "library", diagramId: $id}}')"
}

# The file form, which is what every existing caller meant.
api_show() { api_show_file "$1"; }

api_unwatch() {
  _api_call unwatch "$(_jq -n --arg path "$1" '{path: $path}')"
}

api_get() {
  _api_call get-document "$(_jq -n --arg path "$1" '{path: $path}')"
}

# $1 path, $2 text, $3 baseRevision, $4 source (default "cli").
#
# `--arg` for baseRevision, not `--argjson`: under D1 a revision is a hex
# content hash, and a bare hash is not valid JSON the way a bare integer was.
# `--argjson` dies with "invalid JSON text" on the first hash it is handed.
api_put() {
  _api_call put-document "$(_jq -n \
    --arg path "$1" --arg text "$2" --arg baseRevision "$3" --arg source "${4:-cli}" \
    '{path: $path, text: $text, baseRevision: $baseRevision, source: $source}')"
}

api_push_text() {
  _api_call push-text "$(_jq -n --arg text "$1" '{text: $text}')"
}

# Fetch the current revision, then write against it -- what `gx set` did when no
# --base-revision was given.
api_set() {
  local path="$1" text="$2" source="${3:-cli}" current
  current="$(api_get "${path}")"
  [[ "$(api_last_status)" == "ok" ]] || { echo "${current}"; return 0; }
  api_put "${path}" "${text}" "$(jq -r '.result.document.revision' <<<"${current}")" "${source}"
}
