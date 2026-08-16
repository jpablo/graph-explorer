#!/usr/bin/env bash
#
# The desktop's control API, driven directly.
#
# The smoke gates used to reach the desktop through the Rust `gx`, which made
# the CLI load-bearing for the DESKTOP's test coverage. `gx` is now Scala and
# speaks no HTTP (docs/desktop-gx-v2-architecture.md D2, D4), so the gates talk
# to the API they are actually testing. That is the better arrangement anyway:
# they now assert the contract rather than one client's view of it.
#
# Every path that travels in a URL is encoded HERE and nowhere else. The brief's
# §4 is explicit that a URL-carried path inherits v1's percent-decoding hazard —
# the bug that survived five months because a hand-rolled decoder handled `%2F`
# and nothing else, so a plain POSIX path worked and a path with a space did
# not. One encoder, one place to get it right.
#
# Usage:
#   source "${ROOT_DIR}/scripts/lib/control-api.sh"
#   control_wait_ready 80 || exit 1
#   api_watch "/abs/path.dot"        # body on stdout, code in $API_HTTP_STATUS

CONTROL_RUNTIME_FILE="${CONTROL_RUNTIME_FILE:-${HOME}/.graph-explorer/runtime/control.json}"
CONTROL_PORT=""
CONTROL_TOKEN=""
CONTROL_BASE=""
API_HTTP_STATUS=""

# Read port and token from the runtime file. Returns 1 if it is absent or
# incomplete, which is the ordinary "no desktop yet" case, not an error.
control_load() {
  [[ -f "${CONTROL_RUNTIME_FILE}" ]] || return 1
  CONTROL_PORT="$(jq -r '.port // empty' "${CONTROL_RUNTIME_FILE}" 2>/dev/null || true)"
  CONTROL_TOKEN="$(jq -r '.token // empty' "${CONTROL_RUNTIME_FILE}" 2>/dev/null || true)"
  [[ -n "${CONTROL_PORT}" && -n "${CONTROL_TOKEN}" ]] || return 1
  CONTROL_BASE="http://127.0.0.1:${CONTROL_PORT}"
  return 0
}

# Is the desktop actually answering? The runtime file outlives a crash, so its
# presence proves nothing.
control_ready() {
  control_load || return 1
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer ${CONTROL_TOKEN}" \
    "${CONTROL_BASE}/v1/status" || true)"
  [[ "${code}" == "200" ]]
}

control_wait_ready() {
  local tries="${1:-80}"
  for _ in $(seq 1 "${tries}"); do
    if control_ready; then return 0; fi
    sleep 0.25
  done
  echo "desktop control API did not become ready" >&2
  return 1
}

# Percent-encode for a query value. `@uri` escapes everything outside the
# unreserved set, including `/`, `:`, `\` and spaces — which is exactly what the
# desktop's decoder expects, and exactly what v1's did not handle.
_api_uri_escape() {
  jq -rn --arg v "$1" '$v|@uri'
}

# $1 method, $2 path-with-query, $3 optional JSON body.
# Body on stdout; HTTP code in $API_HTTP_STATUS. Never fails the caller's
# `set -e`, so a script can assert on a 4xx deliberately.
_api_call() {
  local method="$1" endpoint="$2" body="${3:-}"
  control_load || { echo "control runtime file unavailable" >&2; return 1; }
  local out
  out="$(mktemp "${TMPDIR:-/tmp}/gx-api-XXXXXX.json")"
  if [[ -n "${body}" ]]; then
    API_HTTP_STATUS="$(curl -sS -o "${out}" -w '%{http_code}' \
      -X "${method}" "${CONTROL_BASE}${endpoint}" \
      -H "Authorization: Bearer ${CONTROL_TOKEN}" \
      -H 'Content-Type: application/json' \
      --data "${body}" || echo "000")"
  else
    API_HTTP_STATUS="$(curl -sS -o "${out}" -w '%{http_code}' \
      -X "${method}" "${CONTROL_BASE}${endpoint}" \
      -H "Authorization: Bearer ${CONTROL_TOKEN}" || echo "000")"
  fi
  cat "${out}"
  rm -f "${out}"
}

api_status() { _api_call GET /v1/status; }

# `watch` and `unwatch` carry the path in a JSON BODY; `get` carries it in the
# URL. That asymmetry is v1's, kept because it is what the desktop implements —
# and it is the asymmetry that hid the decoding bug, which is why the URL form
# has exactly one encoder above.
api_watch() {
  _api_call POST /v1/watch "$(jq -n --arg path "$1" '{path: $path, openInUi: true}')"
}

api_unwatch() {
  _api_call POST /v1/unwatch "$(jq -n --arg path "$1" '{path: $path}')"
}

api_get() {
  _api_call GET "/v1/document?path=$(_api_uri_escape "$1")"
}

# $1 path, $2 text, $3 baseRevision, $4 source (default "cli").
api_put() {
  _api_call PUT /v1/document "$(jq -n \
    --arg path "$1" --arg text "$2" --argjson baseRevision "$3" --arg source "${4:-cli}" \
    '{path: $path, text: $text, baseRevision: $baseRevision, source: $source}')"
}

# Fetch the current revision, then write against it — what `gx set` did when no
# --base-revision was given.
api_set() {
  local path="$1" text="$2" source="${3:-cli}" current
  current="$(api_get "${path}")"
  [[ "${API_HTTP_STATUS}" == "200" ]] || { echo "${current}"; return 0; }
  api_put "${path}" "${text}" "$(jq -r '.document.revision' <<<"${current}")" "${source}"
}
