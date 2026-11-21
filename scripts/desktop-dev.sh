#!/usr/bin/env bash
# Run the desktop app in Tauri dev mode against the running vite dev server.
#
# The window loads the vite page, so Scala.js and CSS changes show at once,
# as in the browser. Rust changes in desktop/src-tauri restart the window.
#
# tauri.conf.json sets devUrl to http://localhost:5173, but other projects
# often hold that port and vite then moves to the next free port. This script
# finds the port that serves the Graph Explorer page, so the window does not
# load a different app.
#
# Usage:
#   scripts/desktop-dev.sh                       # find the vite port
#   DEV_URL=http://localhost:5174 scripts/desktop-dev.sh
#   GX_HOME=/tmp/gxdev scripts/desktop-dev.sh    # keep gx away from a release app
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
marker='<title>Graph Explorer</title>'

is_graph_explorer() {
  curl -fsS -m 2 "$1/" 2>/dev/null | grep -qF "$marker"
}

if [[ -n "${DEV_URL:-}" ]]; then
  if ! is_graph_explorer "$DEV_URL"; then
    echo "DEV_URL=$DEV_URL does not serve the Graph Explorer page." >&2
    exit 2
  fi
  url="$DEV_URL"
else
  url=""
  for port in $(seq 5173 5199); do
    if is_graph_explorer "http://localhost:$port"; then
      url="http://localhost:$port"
      break
    fi
  done
  if [[ -z "$url" ]]; then
    echo "No Graph Explorer vite server on localhost:5173-5199." >&2
    echo "Start it first: make dev-serve (and make dev-compile)." >&2
    exit 2
  fi
fi

echo "desktop dev window -> $url"
[[ -n "${GX_HOME:-}" ]] && echo "GX_HOME=$GX_HOME"

cd "$root/desktop"
exec cargo tauri dev --config "{\"build\":{\"devUrl\":\"$url\"}}"
