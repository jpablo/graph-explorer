#!/usr/bin/env bash
# Measures the pan and the zoom of the canvas in the system WebKit (Safari and
# the macOS desktop window). See scripts/webkit/pan-zoom-perf.swift.
#
# The script makes a diagram with 360 nodes and 600 arrows (about 5,400 SVG
# elements), opens it in a WebKit window, and sends one wheel event in each
# frame. It prints one JSON line for each gesture: the script time of the wheel
# handler and of the frame callbacks, the frame times, and the time of the DOM
# calls that can force a layout.
#
# Local only: it needs macOS, the Swift compiler, and a running vite dev server
# (make dev-compile and make dev-serve). A test window shows for some seconds.
#
# Usage:
#   scripts/webkit-pan-zoom-perf.sh                       # find the vite port
#   DEV_URL=http://localhost:5174 scripts/webkit-pan-zoom-perf.sh
#   DOT_FILE=path/to/diagram.dot scripts/webkit-pan-zoom-perf.sh   # a diagram in the repository
#   EVENTS=300 scripts/webkit-pan-zoom-perf.sh            # wheel events for each gesture
#   STUB_CTM=1 scripts/webkit-pan-zoom-perf.sh            # experiment: getScreenCTM from a cache
#   CAP_60FPS=1 scripts/webkit-pan-zoom-perf.sh           # keep the 60 fps limit of WebKit on
#   LIMIT_S=600 scripts/webkit-pan-zoom-perf.sh           # stop the run after this time (default 300 s)
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
bin="$build_dir/pan-zoom-perf"
src="$root/scripts/webkit/pan-zoom-perf.swift"
if [[ ! -x "$bin" || "$src" -nt "$bin" ]]; then
  swiftc -O -o "$bin" "$src"
fi

# Vite serves the files of the repository, so the page can fetch the diagram.
if [[ -n "${DOT_FILE:-}" ]]; then
  dot_abs="$(cd "$(dirname "$DOT_FILE")" && pwd)/$(basename "$DOT_FILE")"
  case "$dot_abs" in
    "$root"/*) dot_path="${dot_abs#"$root"}" ;;
    *) echo "DOT_FILE must be in the repository, so that vite serves it." >&2; exit 2 ;;
  esac
else
  dot_path="/target/webkit-checks/pan-zoom-big.dot"
  # 12 groups of 30 nodes, and 600 arrows between random nodes. The seed of
  # awk is fixed, so each run measures the same diagram.
  awk 'BEGIN {
    srand(7)
    print "digraph Big {"
    print "  rankdir=LR;"
    print "  node [shape=box, style=filled, fillcolor=\"#eef\"];"
    n = 0
    for (c = 0; c < 12; c++) {
      printf "  subgraph cluster_%d { label=\"group %d\";\n", c, c
      for (k = 0; k < 30; k++) {
        printf "    n%d [label=\"Node %d\\nfield_a: Int\\nfield_b: String\"];\n", n, n
        n++
      }
      print "  }"
    }
    for (e = 0; e < 600; e++) {
      a = int(rand() * n); b = int(rand() * n)
      if (a == b) continue
      if (e % 5 == 0) printf "  n%d -> n%d [label=\"e%d\"];\n", a, b, e
      else printf "  n%d -> n%d;\n", a, b
    }
    print "}"
  }' >| "$root$dot_path"
fi

# The check exits by itself. The limit stops a check that hangs.
"$bin" "$url/" "$dot_path" "${EVENTS:-240}" &
pid=$!
limit="${LIMIT_S:-300}"
( sleep "$limit"; kill "$pid" 2>/dev/null && echo "FAIL: the measurement did not end in $limit s" >&2 ) &
watchdog=$!
status=0
wait "$pid" || status=$?
kill "$watchdog" 2>/dev/null || true
exit "$status"
