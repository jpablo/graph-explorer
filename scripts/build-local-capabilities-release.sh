#!/usr/bin/env bash
#
# Build the release `graph-explorer-desktop` and `gx` binaries locally.
#
# These steps are deliberately a shell script and NOT an sbt task. They used to
# be `sbt buildLocalCapabilitiesRelease`, which could never work: it ran
# `npm run build` from inside an sbt task, vite resolves `scalajs:main.js` by
# shelling back into sbt, and that nested client queued behind the very task
# waiting for it. A circular wait, not a slow build. Run from a shell, the
# nested `sbt print` reaches an idle server and returns immediately.
#
# The order also matches .github/workflows/release-binaries.yml step for step,
# which is the workflow that actually produces the published binaries. A local
# build that composes these differently is not verifying what ships.
#
# Usage:  ./scripts/build-local-capabilities-release.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

DESKTOP_BIN="desktop/src-tauri/target/release/graph-explorer-desktop"
GX_BIN="gx/target/release/gx"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# An sbt server busy with a `~watch` will queue every command behind it,
# including the one vite issues mid-build. Say so up front rather than let the
# build appear to hang.
if pgrep -f 'sbtn.*~' >/dev/null 2>&1; then
  echo "warning: an sbt watch (\`~task\`) looks active; it holds the server's" >&2
  echo "         exec loop and this build will queue behind it. Stop it first." >&2
fi

step "npm install"
# vite, and the esbuild step in viewer's Test / jsEnvInput, resolve from
# node_modules, and no sbt task installs them.
npm install --no-audit --no-fund

step "sbt viewer/fullLinkJS"
# Redundant in principle -- vite's resolver runs `print viewer/fullLinkJSOutput`,
# which links as a side effect -- but done explicitly so a Scala compile error
# surfaces here instead of halfway through a vite build.
sbt --batch -no-colors -Dsbt.supershell=false viewer/fullLinkJS

step "npm run build"
npm run build

step "force the desktop re-embed"
# Tauri embeds `frontendDist` (../../dist) into the binary at compile time via
# generate_context!. `cargo build` only recompiles when a Rust source changes,
# so a frontend-only change to dist/ is a cargo no-op and the binary silently
# keeps a stale embedded bundle. Bump the entrypoint's mtime to force it.
touch desktop/src-tauri/src/main.rs

step "cargo build: desktop (release)"
# `--features tauri/custom-protocol` is REQUIRED. tauri's build script sets
# `dev = !custom-protocol`, so a release build without it is still a *dev*
# build that loads devUrl (http://localhost:5173) instead of the embedded
# frontend -- a blank window unless a vite dev server happens to be running.
# The Tauri CLI adds the feature automatically; a bare `cargo build` does not.
(cd desktop/src-tauri && cargo build --release --locked --features tauri/custom-protocol)

step "cargo build: gx (release)"
(cd gx && cargo build --release --locked)

step "verify this is a production build"
# Assert the property that matters -- the frontend is EMBEDDED -- rather than
# grepping the build log for `cargo:rustc-cfg=dev`, which only appears when the
# build script re-runs and so passes vacuously on an incremental build.
# A dev build embeds nothing, so a freshly built asset name cannot appear in it.
shopt -s nullglob
index_assets=(dist/assets/index-*.js)
shopt -u nullglob
if (( ${#index_assets[@]} == 0 )); then
  echo "error: no dist/assets/index-*.js found; did the frontend build run?" >&2
  exit 1
fi
asset="$(basename "${index_assets[0]}")"

# `grep -cF`, deliberately, not `grep -qF`: `-q` exits at the first match, and
# with `set -o pipefail` the still-writing `strings` then dies of SIGPIPE (141),
# which pipefail promotes to the pipeline's status -- a successful match
# reported as a failure, intermittently, depending on who finishes first.
# `-c` drains the stream. `-F` keeps the `.` in the filename literal.
embedded="$(strings -a "${DESKTOP_BIN}" | grep -cF -- "${asset}" || true)"
if (( embedded == 0 )); then
  echo "error: ${asset} is NOT embedded in ${DESKTOP_BIN}." >&2
  echo "       This is a dev build and will show a blank window." >&2
  echo "       Check that --features tauri/custom-protocol was applied." >&2
  exit 1
fi
echo "ok: ${asset} is embedded in the desktop binary (${embedded} occurrences)"

step "done"
ls -la "${DESKTOP_BIN}" "${GX_BIN}"
