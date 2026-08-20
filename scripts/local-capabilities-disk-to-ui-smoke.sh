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
# Acceptance (LC2-T5): median write->visible latency <= 300 ms.
# All per-iteration samples are printed so timing is captured in test logs.
#
# The sampling loop runs inside ONE python3 process, and that is load-bearing rather
# than a style choice. It used to be bash, which cannot measure this path: every poll
# iteration forked curl + jq to read the revision and python3 again to check the
# deadline, and `observed` was a fourth fork whose cost landed directly in the reported
# latency. Measured on an M-series laptop -- faster than any CI runner -- that was ~18ms
# per curl|jq and ~22ms per python3 start: ~40ms of harness per poll, plus ~22ms baked
# into every sample. The budget was being spent on fork(), not on disk->UI, which is why
# macOS sat at a 239ms median with a 300ms budget and failed at 308ms on a busy runner
# while Linux -- same code, cheaper forks -- reported 117ms.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_FILE="${HOME}/.graph-explorer/runtime/control.json"
DESKTOP_BIN="${ROOT_DIR}/desktop/src-tauri/target/release/graph-explorer-desktop"

# shellcheck source=scripts/lib/control-api.sh
source "${ROOT_DIR}/scripts/lib/control-api.sh"

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
  running="$(control_ready && echo true || echo false)"
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

watch_json="$(api_watch "${tmpfile}")"
last_revision="$(jq -r '.result.revision' <<<"${watch_json}")"
echo "watch established at revision ${last_revision}; running ${SAMPLES} samples (budget ${MEDIAN_BUDGET_MS}ms median)"

api_socket="$(jq -r '.socket' "${RUNTIME_FILE}")"

# Everything below the rename is timed, so nothing below the rename may fork. Failure is
# captured rather than propagated so the watch is released either way -- the old loop
# exited straight out of the sample and left it registered.
set +e
LC2T5_SAMPLES="${SAMPLES}" \
LC2T5_MEDIAN_BUDGET_MS="${MEDIAN_BUDGET_MS}" \
LC2T5_SAMPLE_TIMEOUT_MS="${PER_SAMPLE_TIMEOUT_MS}" \
python3 - "${tmpfile}" "${api_socket}" "${last_revision}" <<'PY'
import json
import os
import socket
import sys
import time

path, sock_path, start_revision = sys.argv[1], sys.argv[2], sys.argv[3]
samples_n = int(os.environ["LC2T5_SAMPLES"])
budget_ms = int(os.environ["LC2T5_MEDIAN_BUDGET_MS"])
timeout_s = int(os.environ["LC2T5_SAMPLE_TIMEOUT_MS"]) / 1000.0

# The path travels as a JSON string. The quoting this used to do -- and the
# desktop's matching decode -- are gone with the URLs (D4).
request_id = 0
conn = None
buffered = b""


def poll_revision():
    """Current revision, or None.

    One connection is kept alive across polls, which matters more here than it
    did over TCP: this loop's whole job is measuring disk->UI latency, so any
    per-poll setup lands inside the number being reported. A unix socket has no
    handshake to pay for at all.
    """
    global conn, buffered, request_id
    for _ in range(2):
        try:
            if conn is None:
                conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                conn.settimeout(2)
                conn.connect(sock_path)
                buffered = b""
            request_id += 1
            frame = json.dumps(
                {"id": request_id, "method": "get-document", "params": {"path": path}}
            )
            conn.sendall((frame + "\n").encode("utf-8"))
            while b"\n" not in buffered:
                chunk = conn.recv(65536)
                if not chunk:
                    raise OSError("desktop closed the control channel")
                buffered += chunk
            line, _, buffered = buffered.partition(b"\n")
            response = json.loads(line.decode("utf-8"))
            return ((response.get("result") or {}).get("document") or {}).get("revision")
        except Exception:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
            conn = None
    return None


staging = path + ".staging"
# D1: a revision is a content hash, so the signal is CHANGED, not GREATER.
# Ordering was never what this gate needed — it asks "did the desktop notice the
# file move", and a differing hash answers that exactly. Each sample writes
# distinct bytes (the loop index and a millisecond clock), so a change always
# produces a different hash.
last_revision = start_revision
latencies = []

for i in range(1, samples_n + 1):
    # External edit: write a sibling file then rename atomically, the way an editor
    # would (not a `gx set`, so no self-write suppression).
    with open(staging, "w") as handle:
        handle.write("digraph G {\n  n%d -> n%d\n}\n" % (i, int(time.time() * 1000)))

    t0 = time.monotonic()
    os.replace(staging, path)
    deadline = t0 + timeout_s

    while True:
        revision = poll_revision()
        if revision is not None and revision != last_revision:
            observed = time.monotonic()
            last_revision = revision
            break
        if time.monotonic() >= deadline:
            sys.exit(
                "sample %d: revision did not change within %dms (still %s)"
                % (i, timeout_s * 1000, last_revision)
            )
        time.sleep(0.005)

    latency = int(round((observed - t0) * 1000))
    latencies.append(latency)
    print("sample %2d: %4d ms (revision -> %s)" % (i, latency, str(last_revision)[:12]), flush=True)

# Same statistics as the shell version it replaces, so runs stay comparable across the change.
xs = sorted(latencies)
n = len(xs)
median = xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) // 2
p95 = xs[max(0, min(n - 1, round(0.95 * (n - 1))))]
print(
    "disk->UI latency over %d samples: min=%dms median=%dms p95=%dms max=%dms"
    % (n, xs[0], median, p95, xs[-1])
)

if median > budget_ms:
    sys.exit("assertion failed: median %dms exceeds LC2-T5 budget %dms" % (median, budget_ms))

print("disk-to-ui smoke passed (median %dms <= %dms)" % (median, budget_ms))
PY
sample_status=$?
set -e

api_unwatch "${tmpfile}" >/dev/null 2>&1 || true

exit "${sample_status}"
