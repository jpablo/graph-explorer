# Local Capabilities v1 Quickstart

Last updated: 2026-03-01

This guide walks through the first end-to-end local workflow:

1. start desktop runtime
2. connect with `gx`
3. watch a local diagram file
4. edit via CLI/UI path with revision safety

## Prerequisites

- macOS with Rust toolchain (`cargo`) installed
- `jq` and `curl` installed
- this repository checked out locally

## Build desktop + CLI binaries

From repository root:

```bash
sbt --client buildLocalCapabilitiesRelease
```

Equivalent manual commands (run from repository root):

```bash
sbt viewer/fullLinkJS                                   # optimized Scala.js output
npm run build                                           # vite bundles it into dist/
touch desktop/src-tauri/src/main.rs                     # force the re-embed (see note)
(cd desktop/src-tauri && cargo build --release --locked --features tauri/custom-protocol)
(cd gx && cargo build --release --locked)
```

> **Note:** `--features tauri/custom-protocol` is **required** on the desktop
> build. tauri's build script sets `dev = !custom-protocol`; without the
> feature a release `cargo build` is still a *dev* build that loads `devUrl`
> (`http://localhost:5173`) instead of the embedded `dist/`, so the window is
> blank unless a vite dev server is running. (The Tauri CLI adds this feature
> automatically — a bare `cargo build` does not.)
>
> Also: `npm run build` is only `vite build`; it does not run the Scala.js
> compile, so `sbt viewer/fullLinkJS` must come first on a clean checkout.
> Tauri embeds `dist/` at compile time and `cargo build` is a no-op when only
> `dist/` changed, so the entrypoint is touched to force the re-embed. The
> `buildLocalCapabilitiesRelease` task does all of these steps for you.

Binaries:

- desktop: `desktop/src-tauri/target/release/graph-explorer-desktop`
- CLI: `gx/target/release/gx`

## Start runtime and verify connectivity

```bash
desktop/src-tauri/target/release/graph-explorer-desktop &
gx/target/release/gx status --json
```

Expected: JSON response with `"running": true`.

Runtime metadata is written to:

- `~/.graph-explorer/runtime/control.json`

## First watch/edit flow

Create a diagram file and watch it:

```bash
cat > /tmp/diagram.dot <<'EOF'
digraph G {
  a -> b
}
EOF

gx/target/release/gx watch /tmp/diagram.dot --json
gx/target/release/gx get --file /tmp/diagram.dot --json
```

Update with revision-safe write:

```bash
gx/target/release/gx set --file /tmp/diagram.dot --text $'digraph G {\n  b -> c\n}\n' --json
```

If you attempt a stale revision, the CLI exits with code `5` (`DOCUMENT_CONFLICT`).

Stop watching:

```bash
gx/target/release/gx unwatch /tmp/diagram.dot --json
```

## Optional policy controls

Allowlist (only watch files under these roots):

```bash
GX_ALLOWED_ROOTS="/Users/you/projects:/tmp" desktop/src-tauri/target/release/graph-explorer-desktop
```

Denylist extensions (in addition to defaults):

```bash
GX_DENY_ROOTS="/Users/you/secrets:/Volumes/private" desktop/src-tauri/target/release/graph-explorer-desktop
```

Request limits:

```bash
GX_MAX_REQUEST_BODY_BYTES=1048576 \
GX_RATE_LIMIT_MAX_REQUESTS=240 \
GX_RATE_LIMIT_WINDOW_MS=10000 \
desktop/src-tauri/target/release/graph-explorer-desktop
```

## Audit log

Desktop writes metadata-only audit events to:

- `~/.graph-explorer/runtime/audit.log.jsonl`

Events include watch lifecycle, document write/conflict, and rate-limit rejections.

## Troubleshooting

- `DESKTOP_NOT_RUNNING`: start desktop runtime first.
- `DESKTOP_UNREACHABLE`: remove stale `~/.graph-explorer/runtime/control.json` and restart desktop.
- `AUTH_FAILURE`: token in runtime file is stale; restart desktop and retry.
- `INVALID_REQUEST` during `watch`: verify file exists and is not blocked by allowlist/denylist policy.
- `DOCUMENT_CONFLICT`: fetch latest revision (`gx get`) and retry write with current base revision.
- `PAYLOAD_TOO_LARGE`: reduce request payload size or raise `GX_MAX_REQUEST_BODY_BYTES`.
- `RATE_LIMITED`: slow request burst rate or raise `GX_RATE_LIMIT_MAX_REQUESTS`.
- **Blank desktop window** (title bar shows, content white, no stderr): the
  binary was built without `--features tauri/custom-protocol`, so it is a dev
  build pointing at `devUrl` instead of the embedded `dist/`. Rebuild with the
  feature (or use `buildLocalCapabilitiesRelease`). Verify with
  `cargo build --release -v 2>&1 | grep -- '--cfg dev'` — a correct prod build
  prints nothing.
