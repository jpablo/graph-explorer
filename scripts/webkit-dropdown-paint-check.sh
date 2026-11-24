#!/usr/bin/env bash
# Checks that a toolbar dropdown panel is drawn after a click, in the system
# WebKit (Safari and the macOS desktop window). See scripts/webkit/dropdown-paint.swift.
#
# In WebKit 27 the panel opens in the DOM, but the screen does not show it after
# the open animation, so the shape, color and font pickers seem to close at once.
# Chromium and Playwright's WebKit draw the panel correctly, so only this check
# shows the bug.
#
# Local only: it needs macOS, the Swift compiler, a running vite dev server
# (make dev-compile and make dev-serve), and the Screen Recording permission for
# the terminal app. A test window shows for some seconds.
#
# Usage:
#   scripts/webkit-dropdown-paint-check.sh                 # find the vite port
#   DEV_URL=http://localhost:5174 scripts/webkit-dropdown-paint-check.sh
#   OUTPUT_DIR=/tmp/out scripts/webkit-dropdown-paint-check.sh   # keep the window capture
#   EXTRA_CSS='.dropdown-content{animation:none}' scripts/webkit-dropdown-paint-check.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
marker='<title>Graph Explorer</title>'

is_graph_explorer() {
  curl -fsS -m 2 "$1/" 2>/dev/null | grep -qF "$marker"
}

if [[ -n "${DEV_URL:-}" ]]; then
  url="$DEV_URL"
else
  url=""
  for port in $(seq 5173 5199); do
    if is_graph_explorer "http://localhost:$port"; then
      url="http://localhost:$port"
      break
    fi
  done
fi
if [[ -z "$url" ]] || ! is_graph_explorer "$url"; then
  echo "No Graph Explorer vite server found. Start it: make dev-serve (and make dev-compile)." >&2
  exit 2
fi

build_dir="$root/target/webkit-checks"
mkdir -p "$build_dir"
bin="$build_dir/dropdown-paint"
src="$root/scripts/webkit/dropdown-paint.swift"
if [[ ! -x "$bin" || "$src" -nt "$bin" ]]; then
  swiftc -O -o "$bin" "$src"
fi

args=("$url/")
if [[ -n "${OUTPUT_DIR:-}" ]]; then
  mkdir -p "$OUTPUT_DIR"
  args+=("$OUTPUT_DIR")
fi

# The check exits by itself. The limit stops a check that hangs.
"$bin" "${args[@]}" &
pid=$!
( sleep 90; kill "$pid" 2>/dev/null && echo "FAIL: the check did not end in 90 s" >&2 ) &
watchdog=$!
status=0
wait "$pid" || status=$?
kill "$watchdog" 2>/dev/null || true
exit "$status"
