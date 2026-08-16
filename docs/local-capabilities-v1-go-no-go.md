# Local Capabilities v1 Go/No-Go Review

Last updated: 2026-08-16
Decision status: **Go — macOS, Linux, and Windows**

> 2026-08-16: Windows is green. Run 31934667443 of
> `local-capabilities-smoke.yml` on `windows-latest` built the production
> desktop and `gx`, then passed the runtime smoke end to end —
> `status` -> `watch` -> `get` -> `set` -> stale write returning exit `5` ->
> `unwatch`. The `get` that had returned exit `4` since May now succeeds. That
> step carries no `continue-on-error`, so its success is a real gate, not a
> reported warning. macOS and Linux passed the same run, including the
> disk -> UI latency smoke (LC2-T5).
>
> This closes the last open item. LC5-T2 and LC5-T4 are complete.

> 2026-08-15 update: the Windows path defect is **fixed**. Root cause was not
> path normalization at all — it was URL decoding.
> `parse_document_path_from_url` decoded the `path` query parameter by
> replacing `%2F` with `/` and nothing else. That happens to cover a plain
> POSIX path, which is why macOS and Linux passed; it covers nothing else. A
> canonical Windows path needs `\` (`%5C`), `:` (`%3A`) and the `\\?\` verbatim
> prefix's `?` (`%3F`) decoded, so the string that reached the watch-registry
> lookup was still percent-encoded, missed every key, and returned
> "path is not currently watched" -> HTTP 400 -> `gx` exit 4. `watch` and `set`
> pass their paths in a JSON body, never through a URL, which is exactly why
> `watch` succeeded and `get` failed.
>
> The defect was never Windows-specific: it reproduces on macOS with any path
> containing a space (`%20`). `GET /v1/document` now percent-decodes properly
> via `urlencoding::decode` — the exact inverse of the `urlencoding::encode`
> that `gx` uses. Covered by 11 unit tests in `desktop/src-tauri/src/main.rs`
> (POSIX, spaces, Windows verbatim paths, lowercase hex, literal `+`, encoded
> separators, and a gx-encode/desktop-decode round trip) and by a permanent
> spaced-path case in `scripts/local-capabilities-release-smoke.sh`.
>
> Both Windows smoke steps (`local-capabilities-smoke.yml`,
> `release-binaries.yml`) have had `continue-on-error` removed and are now
> publish gates.

> 2026-05-17 update: the original macOS evidence below was produced by a
> desktop binary built without `tauri/custom-protocol`, i.e. a dev build that
> never loaded the embedded UI (blank window). That root cause is fixed; CI now
> builds a true production desktop and validates the frontend + macOS + Linux
> runtime end to end. The one remaining gap is a concrete Windows path bug
> (below), not the broad "unvalidated packaging" the original review assumed.

## Scope summary

Implemented and validated in this branch:

- desktop local control API with token auth and runtime discovery file
- `gx` CLI watch/get/set/status/unwatch flows with revision conflict handling
- two-way sync contract (`/v1/document`) and UI save bridge wiring
- policy hardening:
  - allowlist (`GX_ALLOWED_ROOTS`)
  - default denylist + extensions (`GX_DENY_ROOTS`)
  - request body and rate limits
  - structured local audit logging (metadata only)
- regression and smoke coverage:
  - `sbt test`
  - `npm run build`
  - `scripts/local-capabilities-phase3-smoke.sh`
  - `scripts/local-capabilities-policy-smoke.sh`
  - `scripts/local-capabilities-limits-smoke.sh`

## Evidence snapshot

CI (`.github/workflows/local-capabilities-smoke.yml`), production builds
(`--features tauri/custom-protocol`, frontend built and embedded):

- frontend bundle builds (Scala.js fullLink + vite) and is embedded
- desktop + `gx` build as production binaries on macOS, Linux, and Windows
- prod-build guard fails CI if the desktop ever regresses to a dev build
- macOS and Linux release runtime smoke pass:
  - `status` reachable
  - `watch/get/set/unwatch` successful
  - stale `set --base-revision` returns conflict with exit code `5`
- disk -> UI update path (LC2-T5) validated on macOS/Linux: external edit
  reaches the webview-emit boundary in <=300ms median (local: ~168ms median)
- audit events verified at `~/.graph-explorer/runtime/audit.log.jsonl`
- desktop GUI render verified manually on macOS (control API alone is not
  sufficient evidence — that gap is what hid the dev-build regression)

## Residual gaps

- cross-platform packaging smoke status:
  - macOS desktop binary validated in runtime flow
  - Linux desktop binary validated in runtime flow
  - Windows desktop binary validated in runtime flow (run 31934667443)

No residual gaps block v1 on any of the three platforms.
- browser-driven interactive desktop automation remains limited:
  - command-path and API-path are covered
  - full GUI automation coverage is still optional hardening, not blocking correctness of protocol path

## Risks

- ~~**Observed (Windows): path-normalization defect.**~~ Fixed 2026-08-15; see
  the update at the top. It was URL decoding in `GET /v1/document`, not path
  normalization — normalization was consistent on both sides all along.
- Operational requirement: `npm install` must run before `npm run build` and
  before `sbt test` (the viewer's Scala.js tests are esbuild-bundled from
  node_modules). No sbt task installs node deps itself.
- Packaging/toolchain differences can produce release-time failures.
- Aggressive request limits may need tuning in real workloads.

## Recommendation

Go for v1 on macOS, Linux, and Windows. All three build a production desktop
and pass the runtime smoke in CI, and each platform's smoke is a blocking
publish gate — a regression withholds that platform's binaries rather than
shipping them.

Known limitations regardless of platform:

- local-only trust boundary (no direct hosted web -> filesystem access)
- no collaborative editing / CRDT merge in v1
