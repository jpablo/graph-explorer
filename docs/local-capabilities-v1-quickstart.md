# Local Capabilities v1 Quickstart

> **This describes v1.** `gx` has since been rewritten in Scala (see
> `desktop-gx-v2-architecture.md` D2, D5): it no longer needs a running
> desktop, and its commands take a diagram reference rather than
> `--file`. The desktop half below is still accurate.


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
./scripts/build-local-capabilities-release.sh
```

Equivalent manual commands (run from repository root):

```bash
npm install                                             # required: vite bundles from node_modules
sbt viewer/fullLinkJS                                   # Scala.js -> viewer bundle
npm run build                                           # vite bundles it into dist/
touch desktop/src-tauri/src/main.rs                     # force the re-embed (see note)
(cd desktop/src-tauri && cargo build --release --locked --features tauri/custom-protocol)
./scripts/build-gx.sh
```

> **Note:** `--features tauri/custom-protocol` is **required** on the desktop
> build. tauri's build script sets `dev = !custom-protocol`; without the
> feature a release `cargo build` is still a *dev* build that loads `devUrl`
> (`http://localhost:5173`) instead of the embedded `dist/`, so the window is
> blank unless a vite dev server is running. (The Tauri CLI adds this feature
> automatically — a bare `cargo build` does not.)
>
> Also: `npm install` **must** run first — no sbt task installs node deps —
> and `sbt viewer/fullLinkJS` must precede `npm run build` (which is only
> `vite build`). Tauri embeds `dist/` at compile time and `cargo build` is a
> no-op when only `dist/` changed, so the entrypoint is touched to force the
> re-embed.
>
> **Run these from a shell, never from inside an sbt task.** This used to be an
> sbt task, `buildLocalCapabilitiesRelease`, and it could not complete: vite
> resolves `scalajs:main.js` by shelling back into sbt, and that nested client
> queues behind the very task waiting for it — a circular wait, not a slow
> build. For the same reason, stop any `~viewer/fastLinkJS` watch first: a
> watch owns the sbt server's exec loop, so the build queues behind it and
> looks like a hang.

Binaries:

- desktop: `desktop/src-tauri/target/release/graph-explorer-desktop`
- CLI: `gx-cli/target/gx`

## Start runtime and verify connectivity

```bash
desktop/src-tauri/target/release/graph-explorer-desktop &
gx-cli/target/gx status --json
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

gx-cli/target/gx watch /tmp/diagram.dot --json
gx-cli/target/gx get --file /tmp/diagram.dot --json
```

Update with revision-safe write:

```bash
gx-cli/target/gx set --file /tmp/diagram.dot --text $'digraph G {\n  b -> c\n}\n' --json
```

If you attempt a stale revision, the CLI exits with code `5` (`DOCUMENT_CONFLICT`).

Stop watching:

```bash
gx-cli/target/gx unwatch /tmp/diagram.dot --json
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
  feature (or use `./scripts/build-local-capabilities-release.sh`, which
  asserts this). To check an existing binary, look for the embedded frontend
  rather than for a build-log flag — the log line only appears when cargo
  re-runs the build script, so grepping it passes vacuously on an incremental
  build:

  ```bash
  strings -a desktop/src-tauri/target/release/graph-explorer-desktop | grep -c "$(ls dist/assets/index-*.js | head -1 | xargs basename)"
  ```

  A production build embeds that asset (non-zero count); a dev build embeds
  nothing.
- **The build hangs with no output**: something is running `npm run build` from
  inside an sbt task, or an sbt `~watch` owns the server's exec loop. vite
  resolves `scalajs:main.js` by shelling back into sbt, so a vite build started
  from within sbt waits on a nested sbt client that is queued behind it. Build
  from a shell, and stop `~viewer/fastLinkJS` first. Confirm with
  `ps -o pcpu -p <sbt-server-pid>` — a deadlocked server sits at 0% CPU.
