#!/usr/bin/env bash

# LC2-T5: integration test for the disk -> UI update path.
#
# Exercises the real runtime: an external file edit must propagate through the
# filesystem watcher (detect + debounce + coalesce) to the point where the
# desktop bumps the watch revision and dispatches `document.changed` into the
# webview -- both happen in the same locked critical section in
# spawn_watch_loop(), so the revision increment observed via `gx get` is the
# deterministic, headlessly-observable signal that coincides with the UI push.
# The final webview hop (JS receives the DOM event -> Viewer re-render) is
# covered by the LC1-T4 bridge contract and is sub-millisecond JS.
#
# Acceptance (LC2-T5): median write->visible latency <= 300 ms locally.
# All per-iteration samples are printed so timing is captured in test logs.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
GX_BIN="${ROOT_DIR}/gx/target/release/gx"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/release/graph-explorer-desktop"

SAMPLES="${LC2T5_SAMPLES:-15}"
MEDIAN_BUDGET_MS="${LC2T5_MEDIAN_BUDGET_MS:-300}"
PER_SAMPLE_TIMEOUT_MS="${LC2T5_SAMPLE_TIMEOUT_MS:-5000}"

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

require_cmd jq
require_cmd mktemp
require_cmd python3

now_ms() {
  python3 -c 'import time; print(int(time.time()*1000))'
}

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
"${DESKTOP_BIN}" >/tmp/graph-explorer-desktop-disk-to-ui-smoke.log 2>&1 &
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

tmpfile="$(mktemp /tmp/gx-disk-to-ui-XXXXXX.dot)"
printf 'digraph G {\n  n0 -> n0\n}\n' > "${tmpfile}"

watch_json="$("${GX_BIN}" watch "${tmpfile}" --json)"
last_revision="$(jq -r '.revision' <<<"${watch_json}")"
echo "watch established at revision ${last_revision}; running ${SAMPLES} samples (budget ${MEDIAN_BUDGET_MS}ms median)"

samples=()
for i in $(seq 1 "${SAMPLES}"); do
  # External edit: write to a sibling temp file then atomically rename, the way
  # an external editor would (not a `gx set`, so no self-write suppression).
  staging="${tmpfile}.staging"
  printf 'digraph G {\n  n%s -> n%s\n}\n' "${i}" "$(now_ms)" > "${staging}"
  t0="$(now_ms)"
  mv -f "${staging}" "${tmpfile}"

  deadline=$(( t0 + PER_SAMPLE_TIMEOUT_MS ))
  observed=""
  while :; do
    get_json="$("${GX_BIN}" get --file "${tmpfile}" --json 2>/dev/null || true)"
    rev="$(jq -r '.revision // empty' <<<"${get_json}" 2>/dev/null || true)"
    if [[ -n "${rev}" && "${rev}" -gt "${last_revision}" ]]; then
      observed="$(now_ms)"
      last_revision="${rev}"
      break
    fi
    if [[ "$(now_ms)" -ge "${deadline}" ]]; then
      echo "sample ${i}: no revision bump within ${PER_SAMPLE_TIMEOUT_MS}ms (last revision ${last_revision})" >&2
      exit 1
    fi
    sleep 0.01
  done

  latency=$(( observed - t0 ))
  samples+=("${latency}")
  printf 'sample %2s: %4s ms (revision -> %s)\n' "${i}" "${latency}" "${last_revision}"
done

"${GX_BIN}" unwatch "${tmpfile}" --json >/dev/null

# Stats over collected samples.
read -r min med p95 max < <(
  printf '%s\n' "${samples[@]}" | python3 -c '
import sys
xs = sorted(int(l) for l in sys.stdin if l.strip())
n = len(xs)
def pct(p):
    if n == 1: return xs[0]
    k = max(0, min(n - 1, round((p/100) * (n - 1))))
    return xs[k]
med = xs[n//2] if n % 2 else (xs[n//2 - 1] + xs[n//2]) // 2
print(xs[0], med, pct(95), xs[-1])
'
)

echo "disk->UI latency over ${SAMPLES} samples: min=${min}ms median=${med}ms p95=${p95}ms max=${max}ms"

if [[ "${med}" -gt "${MEDIAN_BUDGET_MS}" ]]; then
  echo "assertion failed: median ${med}ms exceeds LC2-T5 budget ${MEDIAN_BUDGET_MS}ms" >&2
  exit 1
fi

echo "disk-to-ui smoke passed (median ${med}ms <= ${MEDIAN_BUDGET_MS}ms)"
